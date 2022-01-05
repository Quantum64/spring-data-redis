/*
 * Copyright 2015-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.redis.connection.lettuce;

import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.sync.BaseRedisCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.ExceptionTranslationStrategy;
import org.springframework.data.redis.PassThroughExceptionTranslationStrategy;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.ClusterCommandExecutor.ClusterCommandCallback;
import org.springframework.data.redis.connection.ClusterCommandExecutor.MultiKeyClusterCommandCallback;
import org.springframework.data.redis.connection.ClusterCommandExecutor.NodeResult;
import org.springframework.data.redis.connection.RedisClusterNode.SlotRange;
import org.springframework.data.redis.connection.convert.Converters;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;

/**
 * {@code RedisClusterConnection} implementation on top of <a href="https://github.com/mp911de/lettuce">Lettuce</a>
 * Redis client.
 *
 * @author Christoph Strobl
 * @author Mark Paluch
 * @since 1.7
 */
public class LettuceClusterConnection extends LettuceConnection implements DefaultedRedisClusterConnection {

	static final ExceptionTranslationStrategy exceptionConverter = new PassThroughExceptionTranslationStrategy(
			new LettuceExceptionConverter());

	private final Log log = LogFactory.getLog(getClass());

	private ClusterCommandExecutor clusterCommandExecutor;
	private ClusterTopologyProvider topologyProvider;
	private boolean disposeClusterCommandExecutorOnClose;

	/**
	 * Creates new {@link LettuceClusterConnection} using {@link RedisClusterClient} with default
	 * {@link RedisURI#DEFAULT_TIMEOUT_DURATION timeout} and a fresh {@link ClusterCommandExecutor} that gets destroyed on
	 * close.
	 *
	 * @param clusterClient must not be {@literal null}.
	 */
	public LettuceClusterConnection(RedisClusterClient clusterClient) {
		this(new ClusterConnectionProvider(clusterClient, CODEC));
	}

	/**
	 * Creates new {@link LettuceClusterConnection} with default {@link RedisURI#DEFAULT_TIMEOUT_DURATION timeout} using
	 * {@link RedisClusterClient} running commands across the cluster via given {@link ClusterCommandExecutor}.
	 *
	 * @param clusterClient must not be {@literal null}.
	 * @param executor must not be {@literal null}.
	 */
	public LettuceClusterConnection(RedisClusterClient clusterClient, ClusterCommandExecutor executor) {
		this(clusterClient, executor, RedisURI.DEFAULT_TIMEOUT_DURATION);
	}

	/**
	 * Creates new {@link LettuceClusterConnection} with given command {@code timeout} using {@link RedisClusterClient}
	 * running commands across the cluster via given {@link ClusterCommandExecutor}.
	 *
	 * @param clusterClient must not be {@literal null}.
	 * @param timeout must not be {@literal null}.
	 * @param executor must not be {@literal null}.
	 * @since 2.0
	 */
	public LettuceClusterConnection(RedisClusterClient clusterClient, ClusterCommandExecutor executor, Duration timeout) {
		this(new ClusterConnectionProvider(clusterClient, CODEC), executor, timeout);
	}

	/**
	 * Creates new {@link LettuceClusterConnection} using {@link LettuceConnectionProvider} running commands across the
	 * cluster via given {@link ClusterCommandExecutor}.
	 *
	 * @param connectionProvider must not be {@literal null}.
	 * @since 2.0
	 */
	public LettuceClusterConnection(LettuceConnectionProvider connectionProvider) {

		super(null, connectionProvider, RedisURI.DEFAULT_TIMEOUT_DURATION.toMillis(), 0);

		Assert.isTrue(connectionProvider instanceof ClusterConnectionProvider,
				"LettuceConnectionProvider must be a ClusterConnectionProvider.");

		this.topologyProvider = new LettuceClusterTopologyProvider(getClient());
		this.clusterCommandExecutor = new ClusterCommandExecutor(this.topologyProvider,
				new LettuceClusterNodeResourceProvider(getConnectionProvider()), exceptionConverter);
		this.disposeClusterCommandExecutorOnClose = true;
	}

	/**
	 * Creates new {@link LettuceClusterConnection} using {@link LettuceConnectionProvider} running commands across the
	 * cluster via given {@link ClusterCommandExecutor}.
	 *
	 * @param connectionProvider must not be {@literal null}.
	 * @param executor must not be {@literal null}.
	 * @since 2.0
	 */
	public LettuceClusterConnection(LettuceConnectionProvider connectionProvider, ClusterCommandExecutor executor) {
		this(connectionProvider, executor, RedisURI.DEFAULT_TIMEOUT_DURATION);
	}

	/**
	 * Creates new {@link LettuceClusterConnection} using {@link LettuceConnectionProvider} running commands across the
	 * cluster via given {@link ClusterCommandExecutor}.
	 *
	 * @param connectionProvider must not be {@literal null}.
	 * @param executor must not be {@literal null}.
	 * @param timeout must not be {@literal null}.
	 * @since 2.0
	 */
	public LettuceClusterConnection(LettuceConnectionProvider connectionProvider, ClusterCommandExecutor executor,
			Duration timeout) {

		super(null, connectionProvider, timeout.toMillis(), 0);

		Assert.notNull(executor, "ClusterCommandExecutor must not be null.");
		Assert.isTrue(connectionProvider instanceof ClusterConnectionProvider,
				"LettuceConnectionProvider must be a ClusterConnectionProvider.");

		this.topologyProvider = new LettuceClusterTopologyProvider(getClient());
		this.clusterCommandExecutor = executor;
		this.disposeClusterCommandExecutorOnClose = false;
	}

	/**
	 * Creates new {@link LettuceClusterConnection} given a shared {@link StatefulRedisClusterConnection} and
	 * {@link LettuceConnectionProvider} running commands across the cluster via given {@link ClusterCommandExecutor}.
	 *
	 * @param sharedConnection may be {@literal null} if no shared connection used.
	 * @param connectionProvider must not be {@literal null}.
	 * @param clusterTopologyProvider must not be {@literal null}.
	 * @param executor must not be {@literal null}.
	 * @param timeout must not be {@literal null}.
	 * @since 2.1
	 */
	protected LettuceClusterConnection(@Nullable StatefulRedisClusterConnection<byte[], byte[]> sharedConnection,
			LettuceConnectionProvider connectionProvider, ClusterTopologyProvider clusterTopologyProvider,
			ClusterCommandExecutor executor, Duration timeout) {

		super(sharedConnection, connectionProvider, timeout.toMillis(), 0);

		Assert.notNull(executor, "ClusterCommandExecutor must not be null.");

		this.topologyProvider = clusterTopologyProvider;
		this.clusterCommandExecutor = executor;
		this.disposeClusterCommandExecutorOnClose = false;
	}

	/**
	 * @return access to {@link RedisClusterClient} for non-connection access.
	 */
	private RedisClusterClient getClient() {

		LettuceConnectionProvider connectionProvider = getConnectionProvider();

		if (connectionProvider instanceof RedisClientProvider) {
			return (RedisClusterClient) ((RedisClientProvider) getConnectionProvider()).getRedisClient();
		}

		throw new IllegalStateException(String.format("Connection provider %s does not implement RedisClientProvider!",
				connectionProvider.getClass().getName()));
	}

	@Override
	public RedisGeoCommands geoCommands() {
		return new LettuceClusterGeoCommands(this);
	}

	@Override
	public RedisHashCommands hashCommands() {
		return new LettuceClusterHashCommands(this);
	}

	@Override
	public RedisHyperLogLogCommands hyperLogLogCommands() {
		return new LettuceClusterHyperLogLogCommands(this);
	}

	@Override
	public RedisKeyCommands keyCommands() {
		return doGetClusterKeyCommands();
	}

	private LettuceClusterKeyCommands doGetClusterKeyCommands() {
		return new LettuceClusterKeyCommands(this);
	}

	@Override
	public RedisListCommands listCommands() {
		return new LettuceClusterListCommands(this);
	}

	@Override
	public RedisStringCommands stringCommands() {
		return new LettuceClusterStringCommands(this);
	}

	@Override
	public RedisSetCommands setCommands() {
		return new LettuceClusterSetCommands(this);
	}

	@Override
	public RedisZSetCommands zSetCommands() {
		return new LettuceClusterZSetCommands(this);
	}

	@Override
	public RedisClusterServerCommands serverCommands() {
		return new LettuceClusterServerCommands(this);
	}

	@Override
	public String ping() {
		Collection<String> ping = clusterCommandExecutor
				.executeCommandOnAllNodes((LettuceClusterCommandCallback<String>) BaseRedisCommands::ping).resultsAsList();

		for (String result : ping) {
			if (!ObjectUtils.nullSafeEquals("PONG", result)) {
				return "";
			}
		}

		return "PONG";
	}

	@Override
	public String ping(RedisClusterNode node) {

		return clusterCommandExecutor
				.executeCommandOnSingleNode((LettuceClusterCommandCallback<String>) BaseRedisCommands::ping, node).getValue();
	}

	@Override
	public List<RedisClusterNode> clusterGetNodes() {
		return new ArrayList<>(topologyProvider.getTopology().getNodes());
	}

	@Override
	public Set<RedisClusterNode> clusterGetSlaves(RedisClusterNode master) {

		Assert.notNull(master, "Master must not be null!");

		RedisClusterNode nodeToUse = topologyProvider.getTopology().lookup(master);

		return clusterCommandExecutor
				.executeCommandOnSingleNode((LettuceClusterCommandCallback<Set<RedisClusterNode>>) client -> LettuceConverters
						.toSetOfRedisClusterNodes(client.clusterSlaves(nodeToUse.getId())), master)
				.getValue();
	}

	@Override
	public Map<RedisClusterNode, Collection<RedisClusterNode>> clusterGetMasterSlaveMap() {

		List<NodeResult<Collection<RedisClusterNode>>> nodeResults = clusterCommandExecutor.executeCommandAsyncOnNodes(
				(LettuceClusterCommandCallback<Collection<RedisClusterNode>>) client -> Converters
						.toSetOfRedisClusterNodes(client.clusterSlaves(client.clusterMyId())),
				topologyProvider.getTopology().getActiveMasterNodes()).getResults();

		Map<RedisClusterNode, Collection<RedisClusterNode>> result = new LinkedHashMap<>();

		for (NodeResult<Collection<RedisClusterNode>> nodeResult : nodeResults) {
			result.put(nodeResult.getNode(), nodeResult.getValue());
		}

		return result;
	}

	@Override
	public Integer clusterGetSlotForKey(byte[] key) {
		return SlotHash.getSlot(key);
	}

	@Override
	public RedisClusterNode clusterGetNodeForSlot(int slot) {

		Set<RedisClusterNode> nodes = topologyProvider.getTopology().getSlotServingNodes(slot);
		if (nodes.isEmpty()) {
			return null;
		}
		return nodes.iterator().next();
	}

	@Override
	public RedisClusterNode clusterGetNodeForKey(byte[] key) {
		return clusterGetNodeForSlot(clusterGetSlotForKey(key));
	}

	@Override
	public ClusterInfo clusterGetClusterInfo() {

		return clusterCommandExecutor
				.executeCommandOnArbitraryNode((LettuceClusterCommandCallback<ClusterInfo>) client -> new ClusterInfo(
						LettuceConverters.toProperties(client.clusterInfo())))
				.getValue();
	}

	@Override
	public void clusterAddSlots(RedisClusterNode node, int... slots) {

		clusterCommandExecutor.executeCommandOnSingleNode(
				(LettuceClusterCommandCallback<String>) client -> client.clusterAddSlots(slots), node);
	}

	@Override
	public void clusterAddSlots(RedisClusterNode node, SlotRange range) {

		Assert.notNull(range, "Range must not be null.");

		clusterAddSlots(node, range.getSlotsArray());
	}

	@Override
	public Long clusterCountKeysInSlot(int slot) {

		try {
			return getConnection().clusterCountKeysInSlot(slot);
		} catch (Exception ex) {
			throw exceptionConverter.translate(ex);
		}
	}

	@Override
	public void clusterDeleteSlots(RedisClusterNode node, int... slots) {
		clusterCommandExecutor.executeCommandOnSingleNode(
				(LettuceClusterCommandCallback<String>) client -> client.clusterDelSlots(slots), node);
	}

	@Override
	public void clusterDeleteSlotsInRange(RedisClusterNode node, SlotRange range) {

		Assert.notNull(range, "Range must not be null.");

		clusterDeleteSlots(node, range.getSlotsArray());
	}

	@Override
	public void clusterForget(RedisClusterNode node) {

		List<RedisClusterNode> nodes = new ArrayList<>(clusterGetNodes());
		RedisClusterNode nodeToRemove = topologyProvider.getTopology().lookup(node);
		nodes.remove(nodeToRemove);

		this.clusterCommandExecutor.executeCommandAsyncOnNodes(
				(LettuceClusterCommandCallback<String>) client -> client.clusterForget(nodeToRemove.getId()), nodes);
	}

	@Override
	public void clusterMeet(RedisClusterNode node) {

		Assert.notNull(node, "Cluster node must not be null for CLUSTER MEET command!");
		Assert.hasText(node.getHost(), "Node to meet cluster must have a host!");
		Assert.isTrue(node.getPort() > 0, "Node to meet cluster must have a port greater 0!");

		this.clusterCommandExecutor.executeCommandOnAllNodes(
				(LettuceClusterCommandCallback<String>) client -> client.clusterMeet(node.getHost(), node.getPort()));
	}

	@Override
	public void clusterSetSlot(RedisClusterNode node, int slot, AddSlots mode) {

		Assert.notNull(node, "Node must not be null.");
		Assert.notNull(mode, "AddSlots mode must not be null.");

		RedisClusterNode nodeToUse = topologyProvider.getTopology().lookup(node);
		String nodeId = nodeToUse.getId();

		clusterCommandExecutor.executeCommandOnSingleNode((LettuceClusterCommandCallback<String>) client -> {
			switch (mode) {
				case MIGRATING:
					return client.clusterSetSlotMigrating(slot, nodeId);
				case IMPORTING:
					return client.clusterSetSlotImporting(slot, nodeId);
				case NODE:
					return client.clusterSetSlotNode(slot, nodeId);
				case STABLE:
					return client.clusterSetSlotStable(slot);
				default:
					throw new InvalidDataAccessApiUsageException("Invalid import mode for cluster slot: " + slot);
			}
		}, node);
	}

	@Override
	public List<byte[]> clusterGetKeysInSlot(int slot, Integer count) {

		try {
			return getConnection().clusterGetKeysInSlot(slot, count);
		} catch (Exception ex) {
			throw exceptionConverter.translate(ex);
		}
	}

	@Override
	public void clusterReplicate(RedisClusterNode master, RedisClusterNode replica) {

		RedisClusterNode masterNode = topologyProvider.getTopology().lookup(master);
		clusterCommandExecutor.executeCommandOnSingleNode(
				(LettuceClusterCommandCallback<String>) client -> client.clusterReplicate(masterNode.getId()), replica);
	}

	@Override
	public Set<byte[]> keys(RedisClusterNode node, byte[] pattern) {
		return doGetClusterKeyCommands().keys(node, pattern);
	}

	@Override
	public Cursor<byte[]> scan(RedisClusterNode node, ScanOptions options) {
		return doGetClusterKeyCommands().scan(node, options);
	}

	public byte[] randomKey(RedisClusterNode node) {
		return doGetClusterKeyCommands().randomKey(node);
	}

	@Override
	public void select(int dbIndex) {

		if (dbIndex != 0) {
			throw new InvalidDataAccessApiUsageException("Cannot SELECT non zero index in cluster mode.");
		}
	}

	// --> cluster node stuff

	@Override
	public void watch(byte[]... keys) {
		throw new InvalidDataAccessApiUsageException("WATCH is currently not supported in cluster mode.");
	}

	@Override
	public void unwatch() {
		throw new InvalidDataAccessApiUsageException("UNWATCH is currently not supported in cluster mode.");
	}

	@Override
	public void multi() {
		throw new InvalidDataAccessApiUsageException("MULTI is currently not supported in cluster mode.");
	}


	public ClusterCommandExecutor getClusterCommandExecutor() {
		return clusterCommandExecutor;
	}

	@Override
	public void close() throws DataAccessException {

		if (!isClosed() && disposeClusterCommandExecutorOnClose) {
			try {
				clusterCommandExecutor.destroy();
			} catch (Exception ex) {
				log.warn("Cannot properly close cluster command executor", ex);
			}
		}

		super.close();
	}

	/**
	 * Lettuce specific implementation of {@link ClusterCommandCallback}.
	 *
	 * @author Christoph Strobl
	 * @param <T>
	 * @since 1.7
	 */
	protected interface LettuceClusterCommandCallback<T>
			extends ClusterCommandCallback<RedisClusterCommands<byte[], byte[]>, T> {}

	/**
	 * Lettuce specific implementation of {@link MultiKeyClusterCommandCallback}.
	 *
	 * @author Christoph Strobl
	 * @param <T>
	 * @since 1.7
	 */
	protected interface LettuceMultiKeyClusterCommandCallback<T>
			extends MultiKeyClusterCommandCallback<RedisClusterCommands<byte[], byte[]>, T> {}

	/**
	 * Lettuce specific implementation of {@link ClusterNodeResourceProvider}.
	 *
	 * @author Christoph Strobl
	 * @since 1.7
	 */
	static class LettuceClusterNodeResourceProvider implements ClusterNodeResourceProvider, DisposableBean {

		private final LettuceConnectionProvider connectionProvider;
		private volatile @Nullable StatefulRedisClusterConnection<byte[], byte[]> connection;

		LettuceClusterNodeResourceProvider(LettuceConnectionProvider connectionProvider) {
			this.connectionProvider = connectionProvider;
		}

		@Override
		@SuppressWarnings("unchecked")
		public RedisClusterCommands<byte[], byte[]> getResourceForSpecificNode(RedisClusterNode node) {

			Assert.notNull(node, "Node must not be null!");

			if (connection == null) {
				synchronized (this) {
					if (connection == null) {
						this.connection = connectionProvider.getConnection(StatefulRedisClusterConnection.class);
					}
				}
			}

			return connection.getConnection(node.getHost(), node.getPort()).sync();
		}

		@Override
		@SuppressWarnings("unchecked")
		public void returnResourceForSpecificNode(RedisClusterNode node, Object resource) {}

		@Override
		public void destroy() throws Exception {
			if (connection != null) {
				connectionProvider.release(connection);
			}
		}
	}
}
