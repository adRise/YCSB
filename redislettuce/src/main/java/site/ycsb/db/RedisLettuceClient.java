package site.ycsb.db;

import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import site.ycsb.ByteIterator;
import site.ycsb.DB;
import site.ycsb.DBException;
import site.ycsb.Status;
import site.ycsb.StringByteIterator;

import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.masterreplica.MasterReplica;
import io.lettuce.core.masterreplica.StatefulRedisMasterReplicaConnection;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.Delay;
import io.lettuce.core.resource.DirContextDnsResolver;
import io.lettuce.core.KeyValue;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

/**
 * YCSB binding for Redis using Lettuce client.
 */
public class RedisLettuceClient extends DB {

  public static final String HOST_PROPERTY = "redis.host";
  public static final String PORT_PROPERTY = "redis.port";
  public static final int DEFAULT_PORT = 6379;
  public static final String PASSWORD_PROPERTY = "redis.password";
  public static final String CLUSTER_PROPERTY = "redis.cluster";
  public static final String READ_FROM = "redis.readfrom"; // replica_preferred, replica, master_preferred, master
  public static final String TIMEOUT_PROPERTY = "redis.timeout";
  public static final String SSL_PROPERTY = "redis.ssl";

  public static final String CONNECTION_PROPERTY = "redis.con"; // connection mode
  public static final String CONNECTION_SINGLE = "single";  // single connection for all threads
  public static final String CONNECTION_MULTIPLE = "multi"; // multiple connections for all threads
  public static final String MULTI_SIZE_PROPERTY = "multi.size";  // connections amount for multi connection model

  // default Redis Settings which are consistent with our application
  private static final long DEFAULT_CONNECT_TIMEOUT_SECONDS = 10; // 10 seconds
  private static final int DEFAULT_REQUEST_QUEUE_SIZE = 65536; // 2^16 default is 2^31-1
  private static final int DEFAULT_RECONNECT_DELAY_SECONDS = 1;
//  private static final long DEFAULT_TOPOLOGY_REFRESH_PERIOD = 1 * 60 * 60 * 1000L;
  private static final long DEFAULT_COMMAND_TIMEOUT_MILLIS = 100L; // 100ms

  enum ConnectionMode {
    SINGLE, MULTIPLE
  }

  public static final String INDEX_KEY = "_indices";

  private static final AtomicInteger INIT_COUNT = new AtomicInteger(0);

  private static AbstractRedisClient client = null;
  private static ConnectionProvider stringConnectionProvider = null;
  private static boolean isCluster = false;

  /**
   * for redis cluster instances.
   * @param host
   * @param port
   * @param enableSSL
   * @return
   */
  static RedisClusterClient createClusterClient(String host, int port, boolean enableSSL) {
    DefaultClientResources resources = DefaultClientResources.builder()
        .dnsResolver(new DirContextDnsResolver())
        .reconnectDelay(Delay.constant(DEFAULT_RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS))
        .build();
    RedisURI primaryNode = RedisURI.builder()
        .withSsl(enableSSL)
        .withHost(host)
        .withPort(port)
        .build();

    RedisClusterClient clusterClient = RedisClusterClient.create(resources, primaryNode);

    ClusterClientOptions clientOptions = ClusterClientOptions.builder()
        .requestQueueSize(DEFAULT_REQUEST_QUEUE_SIZE)
        .socketOptions(
            SocketOptions.builder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        )
        .timeoutOptions(
            TimeoutOptions.builder()
                .timeoutCommands(true)
                .fixedTimeout(Duration.ofMillis(DEFAULT_COMMAND_TIMEOUT_MILLIS))
                .build()
        )
        .build();
    clusterClient.setOptions(clientOptions);

    return clusterClient;
  }

  static StatefulRedisClusterConnection<String, String> createConnection(
      RedisClusterClient clusterClient, ReadFrom readFrom) {
    StatefulRedisClusterConnection<String, String> clusterConnection = clusterClient.connect(StringCodec.UTF8);
    if (readFrom != null) {
      clusterConnection.setReadFrom(readFrom);
    }
    return clusterConnection;
  }

  /**
   * for redis instances not in a cluster.
   * @param host
   * @param port
   * @param enableSSL
   * @return
   */
  static RedisClient createClient() {
    DefaultClientResources resources = DefaultClientResources.builder()
        .dnsResolver(new DirContextDnsResolver())
        .build();
    RedisClient redisClient = RedisClient.create(resources);

    ClientOptions clientOptions = ClientOptions.builder()
        .requestQueueSize(DEFAULT_REQUEST_QUEUE_SIZE)
        .socketOptions(
            SocketOptions.builder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        )
        .timeoutOptions(
            TimeoutOptions.builder()
                .timeoutCommands(true)
                .fixedTimeout(Duration.ofMillis(DEFAULT_COMMAND_TIMEOUT_MILLIS))
                .build()
        )
        .build();
    redisClient.setOptions(clientOptions);

    return redisClient;
  }

  static StatefulRedisMasterReplicaConnection<String, String> createConnection(RedisClient redisClient,
      List<String> hosts, int port, boolean enableSSL, ReadFrom readFrom) {
    List<RedisURI> nodes = getNodes(hosts, port, enableSSL);
    return createConnection(redisClient, nodes, readFrom);
  }

  static List<RedisURI> getNodes(List<String> hosts, int port, boolean enableSSL) {
    List<RedisURI> nodes = new ArrayList<RedisURI>();
    for (String host : hosts) {
      RedisURI node = RedisURI.builder()
          .withSsl(enableSSL)
          .withHost(host)
          .withPort(port)
          .build();
      nodes.add(node);
    }
    return nodes;
  }

  static StatefulRedisMasterReplicaConnection<String, String> createConnection(RedisClient redisClient,
      List<RedisURI> nodes, ReadFrom readFrom) {
    StatefulRedisMasterReplicaConnection<String, String> masterReplicaConnection =
        MasterReplica.connect(redisClient, StringCodec.UTF8, nodes);
    if (readFrom != null) {
      masterReplicaConnection.setReadFrom(readFrom);
    }
    return masterReplicaConnection;
  }

  private void setupConnection() throws DBException {
    if (client != null) {
      return;
    }

    Properties props = getProperties();
    int port;

    String portString = props.getProperty(PORT_PROPERTY);
    if (portString != null) {
      port = Integer.parseInt(portString);
    } else {
      port = DEFAULT_PORT;
    }
    String host = props.getProperty(HOST_PROPERTY);

    boolean clusterEnabled = Boolean.parseBoolean(props.getProperty(CLUSTER_PROPERTY));
    boolean sslEnabled = Boolean.parseBoolean(props.getProperty(SSL_PROPERTY, "false"));

    ReadFrom readFrom = null;
    String readFromString = props.getProperty(READ_FROM);
    if (readFromString != null) {
      if ("replica_preferred".equals(readFromString)) {
        readFrom = ReadFrom.REPLICA_PREFERRED;
      } else if ("master_preferred".equals(readFromString)) {
        readFrom = ReadFrom.MASTER_PREFERRED;
      } else if ("master".equals(readFromString)) {
        readFrom = ReadFrom.MASTER;
      } else if ("replica".equals(readFromString)) {
        readFrom = ReadFrom.REPLICA;
      } else {
        throw new DBException("unknown readfrom: " + readFromString);
      }
    }

    ConnectionMode connectionMode;
    String connectionModeString = props.getProperty(CONNECTION_PROPERTY, CONNECTION_SINGLE);
    if (CONNECTION_SINGLE.equals(connectionModeString)) {
      connectionMode = ConnectionMode.SINGLE;
    } else if (CONNECTION_MULTIPLE.equals(connectionModeString)) {
      connectionMode = ConnectionMode.MULTIPLE;
    } else {
      throw new DBException("unknown connectionMode: " + connectionModeString);
    }

    if (clusterEnabled) {
      RedisClusterClient clusterClient = createClusterClient(host, port, sslEnabled);
      ConnectionProvider connectionProvider = null;
      if (connectionMode == ConnectionMode.SINGLE) {
        connectionProvider = new SingleConnectionProvider(clusterClient, readFrom);
      } else if (connectionMode == ConnectionMode.MULTIPLE) {
        int processors = Runtime.getRuntime().availableProcessors();
        int amount = Integer.parseInt(props.getProperty(MULTI_SIZE_PROPERTY, Integer.toString(processors)));
        connectionProvider = new MultipleConnectionsProvider(clusterClient, readFrom, amount);
      }

      client = clusterClient;
      stringConnectionProvider = connectionProvider;
      isCluster = true;
    } else {
      List<String> hosts = Arrays.asList(host.split(","));
      RedisClient redisClient = createClient();
      List<RedisURI> nodes = getNodes(hosts, port, sslEnabled);

      ConnectionProvider connectionProvider = null;
      if (connectionMode == ConnectionMode.SINGLE) {
        connectionProvider = new SingleConnectionProvider(redisClient, nodes, readFrom);
      } else if (connectionMode == ConnectionMode.MULTIPLE) {
        int processors = Runtime.getRuntime().availableProcessors();
        int amount = Integer.parseInt(props.getProperty(MULTI_SIZE_PROPERTY, Integer.toString(processors)));
        connectionProvider = new MultipleConnectionsProvider(redisClient, nodes, readFrom, amount);
      }

      client = redisClient;
      stringConnectionProvider = connectionProvider;
      isCluster = false;
    }
  }

  private void shutdownConnection() throws DBException {
    if (stringConnectionProvider != null) {
      try {
        stringConnectionProvider.close();
      } catch(Exception ex) {
        // ignore
      }
      stringConnectionProvider = null;
    }

    if (client != null) {
      client.close();
      client = null;
    }
  }

  private RedisClusterCommands<String, String> getRedisClusterCommands() {
    StatefulConnection<String, String> stringConnection = stringConnectionProvider.getConnection();
    RedisClusterCommands cmds = null;
    if (stringConnection != null) {
      if (isCluster) {
        cmds = ((StatefulRedisClusterConnection<String, String>)stringConnection).sync();
      } else {
        cmds = ((StatefulRedisMasterReplicaConnection<String, String>)stringConnection).sync();
      }
    }
    return cmds;
  }

  /**
   * Initialize any state for this DB.
   * Called once per DB instance; there is one DB instance per client thread.
   */
  public void init() throws DBException {
    // Keep track of number of calls to init (for later cleanup)
    INIT_COUNT.incrementAndGet();

    // Synchronized so that we only have a single
    // connection instance for all the threads.
    synchronized (INIT_COUNT) {
      setupConnection();
    }
  }

  /**
   * Cleanup any state for this DB.
   * Called once per DB instance; there is one DB instance per client thread.
   */
  public void cleanup() throws DBException {
    synchronized (INIT_COUNT) {
      final int curInitCount = INIT_COUNT.decrementAndGet();
      if (curInitCount <= 0) {
        shutdownConnection();
      }
      if (curInitCount < 0) {
        // This should never happen.
        throw new DBException(
            String.format("initCount is negative: %d", curInitCount));
      }
    }
  }

  /**
   * Read a record from the database. Each field/value pair from the result will be stored in a HashMap.
   *
   * @param table The name of the table
   * @param key The record key of the record to read.
   * @param fields The list of fields to read, or null for all of them
   * @param result A HashMap of field/value pairs for the result
   * @return The result of the operation.
   */
  @Override
  public Status read(String table, String key, Set<String> fields, Map<String, ByteIterator> result) {
    RedisClusterCommands<String, String> cmds = getRedisClusterCommands();
    if (fields == null) {
      StringByteIterator.putAllAsByteIterators(result, cmds.hgetall(key));
    } else {
      String[] fieldArray = (String[]) fields.toArray(new String[fields.size()]);
      List<KeyValue<String, String>> values = cmds.hmget(key, fieldArray);

      Iterator<KeyValue<String, String>> fieldValueIterator = values.iterator();

      while (fieldValueIterator.hasNext()) {
        KeyValue<String, String> fieldValue = fieldValueIterator.next();
        result.put(fieldValue.getKey(),
            new StringByteIterator(fieldValue.getValue()));
      }
    }
    return result.isEmpty() ? Status.ERROR : Status.OK;
  }

  private double hash(String key) {
    return key.hashCode();
  }

  /**
   * Perform a range scan for a set of records in the database. Each field/value pair from the result will be stored
   * in a HashMap.
   *
   * @param table The name of the table
   * @param startkey The record key of the first record to read.
   * @param recordcount The number of records to read
   * @param fields The list of fields to read, or null for all of them
   * @param result A Vector of HashMaps, where each HashMap is a set field/value pairs for one record
   * @return The result of the operation.
   */
  @Override
  public Status scan(String table, String startkey, int recordcount, Set<String> fields,
      Vector<HashMap<String, ByteIterator>> result) {
    RedisClusterCommands<String, String> cmds = getRedisClusterCommands();
    List<String> keys = cmds.zrangebyscore(INDEX_KEY,
        Range.from(Range.Boundary.excluding(hash(startkey)), Range.Boundary.excluding(Double.POSITIVE_INFINITY)),
        Limit.from(recordcount));

    HashMap<String, ByteIterator> values;
    for (String key : keys) {
      values = new HashMap<String, ByteIterator>();
      read(table, key, fields, values);
      result.add(values);
    }

    return Status.OK;
  }

  /**
   * Update a record in the database. Any field/value pairs in the specified values HashMap will be written into the
   * record with the specified record key, overwriting any existing values with the same field name.
   *
   * @param table The name of the table
   * @param key The record key of the record to write.
   * @param values A HashMap of field/value pairs to update in the record
   * @return The result of the operation.
   */
  @Override
  public Status update(String table, String key, Map<String, ByteIterator> values) {
    RedisClusterCommands<String, String> cmds = getRedisClusterCommands();
    return cmds.hmset(key, StringByteIterator.getStringMap(values))
        .equals("OK") ? Status.OK : Status.ERROR;
  }

  /**
   * Insert a record in the database. Any field/value pairs in the specified values HashMap will be written into the
   * record with the specified record key.
   *
   * @param table The name of the table
   * @param key The record key of the record to insert.
   * @param values A HashMap of field/value pairs to insert in the record
   * @return The result of the operation.
   */
  @Override
  public Status insert(String table, String key, Map<String, ByteIterator> values) {
    RedisClusterCommands<String, String> cmds = getRedisClusterCommands();
    if (cmds.hmset(key, StringByteIterator.getStringMap(values))
        .equals("OK")) {
      cmds.zadd(INDEX_KEY, hash(key), key);
      return Status.OK;
    }
    return Status.ERROR;
  }

  /**
   * Delete a record from the database.
   *
   * @param table The name of the table
   * @param key The record key of the record to delete.
   * @return The result of the operation.
   */
  @Override
  public Status delete(String table, String key) {
    RedisClusterCommands<String, String> cmds = getRedisClusterCommands();
    return cmds.del(key) == 0 && cmds.zrem(INDEX_KEY, key) == 0 ? Status.ERROR
        : Status.OK;
  }

  private interface ConnectionProvider extends AutoCloseable {
    StatefulConnection<String, String> getConnection();
  }

  private static class SingleConnectionProvider implements ConnectionProvider {

    private StatefulConnection<String, String> stringConnection = null;

    public SingleConnectionProvider(RedisClusterClient clusterClient, ReadFrom readFrom) {
      this.stringConnection = createConnection(clusterClient, readFrom);
    }

    public SingleConnectionProvider(RedisClient redisClient, List<RedisURI> nodes, ReadFrom readFrom) {
      this.stringConnection = createConnection(redisClient, nodes, readFrom);
    }

    @Override
    public StatefulConnection<String, String> getConnection() {
      return stringConnection;
    }

    @Override
    public void close() throws Exception {
      stringConnection.close();
    }
  }

  private static class MultipleConnectionsProvider implements ConnectionProvider {
    private List<StatefulConnection<String, String>> stringConnections =
        new ArrayList<StatefulConnection<String, String>>();

    public MultipleConnectionsProvider(RedisClusterClient clusterClient, ReadFrom readFrom, int amount) {
      for (int i = 0; i < amount; i++) {
        this.stringConnections.add(createConnection(clusterClient, readFrom));
      }
    }

    public MultipleConnectionsProvider(RedisClient redisClient, List<RedisURI> nodes, ReadFrom readFrom, int amount) {
      for (int i = 0; i < amount; i++) {
        this.stringConnections.add(createConnection(redisClient, nodes, readFrom));
      }
    }

    @Override
    public StatefulConnection<String, String> getConnection() {
      ThreadLocalRandom random = ThreadLocalRandom.current();
      return this.stringConnections.get(random.nextInt(0, this.stringConnections.size()));
    }

    @Override
    public void close() throws Exception {
      for (StatefulConnection<String, String> stringConnection: stringConnections) {
        stringConnection.close();
      }
      stringConnections.clear();
    }
  }

}
