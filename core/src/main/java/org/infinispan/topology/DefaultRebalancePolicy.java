/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */

package org.infinispan.topology;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.distribution.ch.ConsistentHashFactory;
import org.infinispan.factories.annotations.ComponentName;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.Transport;
import org.infinispan.util.concurrent.ConcurrentMapFactory;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import static org.infinispan.factories.KnownComponentNames.ASYNC_TRANSPORT_EXECUTOR;

/**
 * Default implementation of {@link RebalancePolicy}
 *
 * @author Dan Berindei
 * @since 5.2
 */
public class DefaultRebalancePolicy implements RebalancePolicy {
   private static Log log = LogFactory.getLog(DefaultRebalancePolicy.class);

   private Transport transport;
   private ClusterTopologyManager clusterTopologyManager;
   private ExecutorService asyncTransportExecutor;
   private GlobalConfiguration globalConfiguration;

   private volatile List<Address> clusterMembers;
   private final ConcurrentMap<String, CacheStatus> cacheStatusMap = ConcurrentMapFactory.makeConcurrentMap();

   @Inject
   public void inject(Transport transport, ClusterTopologyManager clusterTopologyManager,
                      @ComponentName(ASYNC_TRANSPORT_EXECUTOR) ExecutorService asyncTransportExecutor,
                      GlobalConfiguration globalConfiguration) {
      this.transport = transport;
      this.clusterTopologyManager = clusterTopologyManager;
      this.asyncTransportExecutor = asyncTransportExecutor;
      this.globalConfiguration = globalConfiguration;
   }

   // must start before ClusterTopologyManager
   @Start(priority = 99)
   public void start() {
      this.clusterMembers = transport.getMembers();
   }

   @Override
   public void initCache(String cacheName, CacheJoinInfo joinInfo) throws Exception {
      log.tracef("Initializing rebalance policy for cache %s", cacheName);
      cacheStatusMap.putIfAbsent(cacheName, new CacheStatus(joinInfo));
   }

   @Override
   public void initCache(String cacheName, List<CacheTopology> partitionTopologies) throws Exception {
      log.tracef("Initializing rebalance policy for cache %s, pre-existing partitions are %s", cacheName, partitionTopologies);
      CacheStatus cacheStatus = cacheStatusMap.get(cacheName);
      if (partitionTopologies.isEmpty())
         return;

      int unionTopologyId = 0;
      ConsistentHash currentCHUnion = null;
      ConsistentHash pendingCHUnion = null;
      ConsistentHashFactory chFactory = cacheStatus.getJoinInfo().getConsistentHashFactory();
      for (CacheTopology topology : partitionTopologies) {
         if (topology.getTopologyId() > unionTopologyId) {
            unionTopologyId = topology.getTopologyId();
         }
         if (currentCHUnion == null) {
            currentCHUnion = topology.getCurrentCH();
         } else {
            currentCHUnion = chFactory.union(currentCHUnion, topology.getCurrentCH());
         }

         if (pendingCHUnion == null) {
            pendingCHUnion = topology.getPendingCH();
         } else {
            if (topology.getPendingCH() != null)
            pendingCHUnion = chFactory.union(pendingCHUnion, topology.getPendingCH());
         }
      }

      synchronized (cacheStatus) {
         CacheTopology cacheTopology = new CacheTopology(unionTopologyId, currentCHUnion, pendingCHUnion);
         updateConsistentHash(cacheName, cacheStatus, cacheTopology, true);
         // TODO Trigger a new rebalance
      }
   }

   /**
    * Should only be called while holding the cacheStatus lock
    */
   private void updateConsistentHash(String cacheName, CacheStatus cacheStatus, CacheTopology cacheTopology,
                                     boolean broadcast) throws Exception {
      log.tracef("Updating cache %s topology: %s", cacheName, cacheTopology);
      cacheStatus.setCacheTopology(cacheTopology);
      ConsistentHash currentCH = cacheTopology.getCurrentCH();
      if (currentCH != null) {
         cacheStatus.getJoiners().removeAll(currentCH.getMembers());
         log.tracef("Updated joiners list for cache %s: %s", cacheName, cacheStatus.getJoiners());
      }
      if (broadcast) {
         clusterTopologyManager.updateConsistentHash(cacheName, cacheStatus.getCacheTopology());
      }
   }

   @Override
   public void updateMembersList(List<Address> newClusterMembers) throws Exception {
      this.clusterMembers = newClusterMembers;
      log.tracef("Updating cluster members for all the caches. New list is %s", newClusterMembers);

      for (Map.Entry<String, CacheStatus> e : cacheStatusMap.entrySet()) {
         String cacheName = e.getKey();
         CacheStatus cacheStatus = e.getValue();
         synchronized (cacheStatus) {
            //cacheStatus.joiners.retainAll(newClusterMembers);
            ConsistentHash currentCH = cacheStatus.getCacheTopology().getCurrentCH();
            // the consistent hash may not be initialized yet
            if (currentCH == null)
               continue;
            ConsistentHash pendingCH = cacheStatus.getCacheTopology().getPendingCH();
            boolean currentMembersValid = newClusterMembers.containsAll(currentCH.getMembers());
            boolean pendingMembersValid = pendingCH == null || newClusterMembers.containsAll(pendingCH.getMembers());
            if (!currentMembersValid || !pendingMembersValid) {
               List<Address> newCurrentMembers = new ArrayList<Address>(currentCH.getMembers());
               newCurrentMembers.retainAll(newClusterMembers);
               updateCacheMembers(cacheName, cacheStatus, newCurrentMembers);
            }

            if (!isBalanced(cacheStatus.getCacheTopology().getCurrentCH()) || !cacheStatus.getJoiners().isEmpty()) {
               // Rebalance after a leave.
               // Also, in rare cases we get the join request from a new node before JGroups has installed the new view
               // If that happens, we re-trigger the rebalance after the view containing it has been installed.
               triggerRebalance(cacheName, cacheStatus);
            }
         }
      }
   }

   @Override
   public CacheTopology addJoiners(String cacheName, List<Address> joiners) throws Exception {
      CacheStatus cacheStatus = cacheStatusMap.get(cacheName);
      if (cacheStatus == null) {
         log.tracef("Ignoring members update for cache %s, as we haven't initialized it yet", cacheName);
         return null;
      }

      synchronized (cacheStatus) {
         addUniqueJoiners(cacheStatus.getJoiners(), joiners);

         ConsistentHash currentCH = cacheStatus.getCacheTopology().getCurrentCH();
         if (currentCH == null) {
            installInitialTopology(cacheName, cacheStatus);
         } else {
            triggerRebalance(cacheName, cacheStatus);
         }
         return cacheStatus.getCacheTopology();
      }
   }

   @Override
   public void removeLeavers(String cacheName, List<Address> leavers) throws Exception {
      CacheStatus cacheStatus = cacheStatusMap.get(cacheName);
      if (cacheStatus == null) {
         log.tracef("Ignoring members update for cache %s, as we haven't initialized it yet", cacheName);
         return;
      }

      synchronized (cacheStatus) {
         // The list of "current" members will always be included in the set of "pending" members,
         // because leaves are reflected at the same time in both collections
         List<Address> newMembers = new ArrayList<Address>(clusterMembers);
         newMembers.removeAll(leavers);

         updateCacheMembers(cacheName, cacheStatus, newMembers);
      }
   }

   private void updateCacheMembers(String cacheName, CacheStatus cacheStatus, List<Address> newMembers)
         throws Exception {
      CacheJoinInfo joinInfo = cacheStatus.getJoinInfo();
      int topologyId = cacheStatus.getCacheTopology().getTopologyId();
      ConsistentHash currentCH = cacheStatus.getCacheTopology().getCurrentCH();
      ConsistentHash pendingCH = cacheStatus.getCacheTopology().getPendingCH();

      ConsistentHash newPendingCH = null;
      if (pendingCH != null) {
         newMembers.retainAll(pendingCH.getMembers());
         if (!newMembers.isEmpty()) {
            newPendingCH = joinInfo.getConsistentHashFactory().updateMembers(pendingCH, newMembers);
         } else {
            log.tracef("Zero new members remaining for cache %s", cacheName);
         }
      }

      newMembers.retainAll(currentCH.getMembers());
      ConsistentHash newCurrentCH;
      if (!newMembers.isEmpty()) {
         newCurrentCH = joinInfo.getConsistentHashFactory().updateMembers(currentCH, newMembers);
      } else {
         log.tracef("Zero old members remaining for cache %s", cacheName);
         // use the new pending CH, it might be non-null if we have joiners
         newCurrentCH = newPendingCH;
      }

      boolean hasMembers = newCurrentCH != null;
      CacheTopology cacheTopology = new CacheTopology(topologyId, newCurrentCH, newPendingCH);

      // Don't broadcast a cache topology when we don't have any members left
      updateConsistentHash(cacheName, cacheStatus, cacheTopology, hasMembers);

      // Don't trigger a rebalance without any members either
      if (hasMembers) {
         triggerRebalance(cacheName, cacheStatus);
      }
   }

   private void installInitialTopology(String cacheName, CacheStatus cacheStatus) throws Exception {
      CacheJoinInfo joinInfo = cacheStatus.getJoinInfo();
      int topologyId = cacheStatus.getCacheTopology().getTopologyId();
      ConsistentHash balancedCH = joinInfo.getConsistentHashFactory().create(joinInfo.getHashFunction(),
            joinInfo.getNumOwners(), joinInfo.getNumSegments(), cacheStatus.getJoiners());
      int newTopologyId = topologyId + 1;
      CacheTopology cacheTopology = new CacheTopology(newTopologyId, balancedCH, null);

      log.tracef("Installing initial topology for cache %s: %s", cacheName, cacheTopology);
      updateConsistentHash(cacheName, cacheStatus, cacheTopology, false);
   }

   private void addUniqueJoiners(List<Address> members, List<Address> joiners) {
      for (Address joiner : joiners) {
         if (!members.contains(joiner)) {
            members.add(joiner);
         }
      }
   }

   private void triggerRebalance(final String cacheName, final CacheStatus cacheStatus) throws Exception {
      asyncTransportExecutor.submit(new Callable<Object>() {
         @Override
         public Object call() throws Exception {
            doRebalance(cacheName, cacheStatus);
            return null;
         }
      });
   }

   private void doRebalance(String cacheName, CacheStatus cacheStatus) throws Exception {
      CacheTopology cacheTopology = cacheStatus.getCacheTopology();
      CacheTopology newCacheTopology;

      synchronized (cacheStatus) {
         boolean isRebalanceInProgress = cacheTopology.getPendingCH() != null;
         if (isRebalanceInProgress) {
            log.tracef("Ignoring request to rebalance cache %s, there's already a rebalance in progress: %s",
                  cacheName, cacheTopology);
            return;
         }

         List<Address> newMembers = new ArrayList<Address>(cacheTopology.getMembers());
         if (newMembers.isEmpty()) {
            log.tracef("Ignoring request to rebalance cache %s, it doesn't have any member", cacheName);
            return;
         }

         addUniqueJoiners(newMembers, cacheStatus.getJoiners());
         newMembers.retainAll(clusterMembers);

         log.tracef("Rebalancing consistent hash for cache %s, members are %s", cacheName, newMembers);
         int newTopologyId = cacheTopology.getTopologyId() + 1;
         ConsistentHash currentCH = cacheTopology.getCurrentCH();
         if (currentCH == null) {
            // There was one node in the cache before, and it left after the rebalance was triggered
            // but before the rebalance actually started.
            installInitialTopology(cacheName, cacheStatus);
            return;
         }

         ConsistentHashFactory chFactory = cacheStatus.getJoinInfo().getConsistentHashFactory();
         ConsistentHash updatedMembersCH = chFactory.updateMembers(currentCH, newMembers);
         ConsistentHash balancedCH = chFactory.rebalance(updatedMembersCH);
         if (balancedCH.equals(currentCH)) {
            log.tracef("The balanced CH is the same as the current CH, not rebalancing");
            return;
         }
         newCacheTopology = new CacheTopology(newTopologyId, currentCH, balancedCH);
         log.tracef("Updating cache %s topology for rebalance: %s", cacheName, newCacheTopology);
         cacheStatus.setCacheTopology(newCacheTopology);
      }

      clusterTopologyManager.rebalance(cacheName, newCacheTopology);
   }

   @Override
   public void onRebalanceCompleted(String cacheName, int topologyId) throws Exception {
      log.debugf("Finished cluster-wide rebalance for cache %s, topology id = %d",
            cacheName, topologyId);
      CacheStatus cacheStatus = cacheStatusMap.get(cacheName);
      synchronized (cacheStatus) {
         if (topologyId != cacheStatus.getCacheTopology().getTopologyId()) {
            throw new IllegalStateException(String.format("Invalid cluster-wide rebalance confirmation: received topology id %d, expected %d",
                  topologyId, cacheStatus.getCacheTopology().getTopologyId()));
         }
         int newTopologyId = topologyId + 1;
         ConsistentHash newCurrentCH = cacheStatus.getCacheTopology().getPendingCH();

         CacheTopology cacheTopology = new CacheTopology(newTopologyId, newCurrentCH, null);
         updateConsistentHash(cacheName, cacheStatus, cacheTopology, true);

         // Update the list of joiners
         // TODO Add some cleanup for nodes that left the cluster before getting any state
         cacheStatus.getJoiners().removeAll(newCurrentCH.getMembers());
         log.tracef("After rebalance, joiners without state are %s", cacheStatus.getJoiners());

         // If we have postponed some joiners, start a new rebalance for them now
         // If the CH is still not balanced (perhaps because of a leaver), restart the rebalance process
         if (cacheStatus.getJoiners().isEmpty() && isBalanced(newCurrentCH)) {
            log.tracef("Consistent hash is now balanced for cache %s", cacheName);
         } else {
            triggerRebalance(cacheName, cacheStatus);
         }
      }
   }

   @Override
   public CacheTopology getTopology(String cacheName) {
      return cacheStatusMap.get(cacheName).cacheTopology;
   }

   // TODO Need a proper API for this
   public boolean isBalanced(ConsistentHash ch) {
      int numSegments = ch.getNumSegments();
      for (int i = 0; i < numSegments; i++) {
         int actualNumOwners = Math.min(ch.getMembers().size(), ch.getNumOwners());
         if (ch.locateOwnersForSegment(i).size() != actualNumOwners) {
            return false;
         }
      }
      return true;
   }

   private static class CacheStatus {
      private final CacheJoinInfo joinInfo;
      private final List<Address> joiners;

      private CacheTopology cacheTopology;

      public CacheStatus(CacheJoinInfo joinInfo) {
         this.joinInfo = joinInfo;

         this.cacheTopology = new CacheTopology(-1, null, null);
         this.joiners = new ArrayList<Address>();
      }

      public CacheJoinInfo getJoinInfo() {
         return joinInfo;
      }

      public List<Address> getJoiners() {
         return joiners;
      }

      public CacheTopology getCacheTopology() {
         return cacheTopology;
      }

      public void setCacheTopology(CacheTopology cacheTopology) {
         this.cacheTopology = cacheTopology;
      }

      @Override
      public String toString() {
         return "CacheStatus{" +
               "joinInfo=" + joinInfo +
               ", cacheTopology=" + cacheTopology +
               ", joiners=" + joiners +
               '}';
      }
   }
}
