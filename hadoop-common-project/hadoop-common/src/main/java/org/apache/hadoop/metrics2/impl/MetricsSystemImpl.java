/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.metrics2.impl;

import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.*;
import java.util.Map.Entry;
import javax.management.ObjectName;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.annotations.VisibleForTesting;

import static com.google.common.base.Preconditions.*;

import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.math3.util.ArithmeticUtils;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.metrics2.MetricsInfo;
import org.apache.hadoop.metrics2.MetricsCollector;
import org.apache.hadoop.metrics2.MetricsException;
import org.apache.hadoop.metrics2.MetricsFilter;
import org.apache.hadoop.metrics2.MetricsRecordBuilder;
import org.apache.hadoop.metrics2.MetricsSink;
import org.apache.hadoop.metrics2.MetricsSource;
import org.apache.hadoop.metrics2.MetricsSystem;
import org.apache.hadoop.metrics2.MetricsTag;
import org.apache.hadoop.metrics2.annotation.Metric;
import org.apache.hadoop.metrics2.annotation.Metrics;
import org.apache.hadoop.metrics2.lib.MutableCounterLong;
import static org.apache.hadoop.metrics2.impl.MetricsConfig.*;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.metrics2.lib.Interns;
import org.apache.hadoop.metrics2.lib.MetricsAnnotations;
import org.apache.hadoop.metrics2.lib.MetricsRegistry;
import org.apache.hadoop.metrics2.lib.MetricsSourceBuilder;
import org.apache.hadoop.metrics2.lib.MutableStat;
import org.apache.hadoop.metrics2.util.MBeans;
import org.apache.hadoop.util.Time;

/**
 * A base class for metrics system singletons
 */
@InterfaceAudience.Private
@Metrics(context="metricssystem")
//生成一个 metricssystem 的注解
public class MetricsSystemImpl extends MetricsSystem implements MetricsSource {

  static final Log LOG = LogFactory.getLog(MetricsSystemImpl.class);
  static final String MS_NAME = "MetricsSystem";
  static final String MS_STATS_NAME = MS_NAME +",sub=Stats";
  static final String MS_STATS_DESC = "Metrics system metrics";
  static final String MS_CONTROL_NAME = MS_NAME +",sub=Control";
  static final String MS_INIT_MODE_KEY = "hadoop.metrics.init.mode";

  enum InitMode { NORMAL, STANDBY }

  private final Map<String, MetricsSourceAdapter> sources;
  private final Map<String, MetricsSource> allSources;
  private final Map<String, MetricsSinkAdapter> sinks;
  private final Map<String, MetricsSink> allSinks;

  // The callback list is used by register(Callback callback), while
  // the callback map is used by register(String name, String desc, T sink)
  private final List<Callback> callbacks;
  private final Map<String, Callback> namedCallbacks;

  private final MetricsCollectorImpl collector;
  private final MetricsRegistry registry = new MetricsRegistry(MS_NAME);
  @Metric({"Snapshot", "Snapshot stats"}) MutableStat snapshotStat;
  @Metric({"Publish", "Publishing stats"}) MutableStat publishStat;
  @Metric("Dropped updates by all sinks") MutableCounterLong droppedPubAll;

  private final List<MetricsTag> injectedTags;

  // Things that are changed by init()/start()/stop()
  private String prefix;
  private MetricsFilter sourceFilter;
  private MetricsConfig config;
  private Map<String, MetricsConfig> sourceConfigs, sinkConfigs;
  private boolean monitoring = false;
  private Timer timer;
  private int period; // seconds
  private long logicalTime; // number of timer invocations * period
  private ObjectName mbeanName;
  private boolean publishSelfMetrics = true;
  private MetricsSourceAdapter sysSource;
  private int refCount = 0; // for mini cluster mode

  /**
   * Construct the metrics system
   * @param prefix  for the system
   */
  public MetricsSystemImpl(String prefix) {
    this.prefix = prefix;
    allSources = Maps.newHashMap();
    sources = Maps.newLinkedHashMap();
    allSinks = Maps.newHashMap();
    sinks = Maps.newLinkedHashMap();
    sourceConfigs = Maps.newHashMap();
    sinkConfigs = Maps.newHashMap();
    callbacks = Lists.newArrayList();
    namedCallbacks = Maps.newHashMap();
    injectedTags = Lists.newArrayList();
    collector = new MetricsCollectorImpl();
    if (prefix != null) {
      // prefix could be null for default ctor, which requires init later
      initSystemMBean();
    }
  }

  /**
   * Construct the system but not initializing (read config etc.) it.
   */
  public MetricsSystemImpl() {
    this(null);
  }

  /**
   * Initialized the metrics system with a prefix.
   * @param prefix  the system will look for configs with the prefix
   * @return the metrics system object itself
   */
  @Override
  public synchronized MetricsSystem init(String prefix) {
    if (monitoring && !DefaultMetricsSystem.inMiniClusterMode()) {
      LOG.warn(this.prefix +" metrics system already initialized!");
      return this;
    }
    this.prefix = checkNotNull(prefix, "prefix");
    ++refCount;
    if (monitoring) {
      // in mini cluster mode
      LOG.info(this.prefix +" metrics system started (again)");
      return this;
    }
    switch (initMode()) {
      case NORMAL:
        try { start(); }
        catch (MetricsConfigException e) {
          // Configuration errors (e.g., typos) should not be fatal.
          // We can always start the metrics system later via JMX.
          LOG.warn("Metrics system not started: "+ e.getMessage());
          LOG.debug("Stacktrace: ", e);
        }
        break;
      case STANDBY:
        LOG.info(prefix +" metrics system started in standby mode");
    }
    initSystemMBean();
    return this;
  }

  //1.启动一个定时器，并且执行对应的回调
  @Override
  public synchronized void start() {
    checkNotNull(prefix, "prefix");
    if (monitoring) {
      LOG.warn(prefix +" metrics system already started!",
               new MetricsException("Illegal start"));
      return;
    }
    for (Callback cb : callbacks) cb.preStart();
    for (Callback cb : namedCallbacks.values()) cb.preStart();
    configure(prefix);
    startTimer();
    monitoring = true;
    LOG.info(prefix +" metrics system started");
    for (Callback cb : callbacks) cb.postStart();
    for (Callback cb : namedCallbacks.values()) cb.postStart();
  }

  @Override
  public synchronized void stop() {
    if (!monitoring && !DefaultMetricsSystem.inMiniClusterMode()) {
      LOG.warn(prefix +" metrics system not yet started!",
               new MetricsException("Illegal stop"));
      return;
    }
    if (!monitoring) {
      // in mini cluster mode
      LOG.info(prefix +" metrics system stopped (again)");
      return;
    }
    for (Callback cb : callbacks) cb.preStop();
    for (Callback cb : namedCallbacks.values()) cb.preStop();
    LOG.info("Stopping "+ prefix +" metrics system...");
    stopTimer();
    stopSources();
    stopSinks();
    clearConfigs();
    monitoring = false;
    LOG.info(prefix +" metrics system stopped.");
    for (Callback cb : callbacks) cb.postStop();
    for (Callback cb : namedCallbacks.values()) cb.postStop();
  }


  // 注册 source
  @Override public synchronized <T>
  T register(String name, String desc, T source) {


    // 在注册时 通过 SourceBuilder 来把source 的 metrics 注册
    // 如果是注解的 Metrics 就会在 SourceBuilder 中 创建一个新的 MetricsSource 类并实现其中的 getMetrics 方法后返回，
    // 注解的 getMetrics 方法的实现内容是 registry.snapshot(builder.addRecord(registry.info()), all);
    //

    MetricsSourceBuilder sb = MetricsAnnotations.newSourceBuilder(source);

    final MetricsSource s = sb.build();

    MetricsInfo si = sb.info();
    String name2 = name == null ? si.name() : name;
    final String finalDesc = desc == null ? si.description() : desc;
    final String finalName = // be friendly to non-metrics tests
        DefaultMetricsSystem.sourceName(name2, !monitoring);
    allSources.put(finalName, s);
    LOG.debug(finalName +", "+ finalDesc);
    if (monitoring) {
      registerSource(finalName, finalDesc, s);
    }
    // We want to re-register the source to pick up new config when the
    // metrics system restarts.
    register(finalName, new AbstractCallback() {
      @Override public void postStart() {
        registerSource(finalName, finalDesc, s);
      }
    });
    return source;
  }

  @Override public synchronized
  void unregisterSource(String name) {
    if (sources.containsKey(name)) {
      sources.get(name).stop();
      sources.remove(name);
    }
    if (allSources.containsKey(name)) {
      allSources.remove(name);
    }
    if (namedCallbacks.containsKey(name)) {
      namedCallbacks.remove(name);
    }
  }

  synchronized
  void registerSource(String name, String desc, MetricsSource source) {
    checkNotNull(config, "config");
    MetricsConfig conf = sourceConfigs.get(name);
    MetricsSourceAdapter sa = conf != null
        ? new MetricsSourceAdapter(prefix, name, desc, source,
                                   injectedTags, period, conf)
        : new MetricsSourceAdapter(prefix, name, desc, source,
          injectedTags, period, config.subset(SOURCE_KEY));
    sources.put(name, sa);
    sa.start();
    LOG.debug("Registered source "+ name);
  }


  // 注册sink
  @Override public synchronized <T extends MetricsSink>
  T register(final String name, final String description, final T sink) {
    LOG.debug(name +", "+ description);
    if (allSinks.containsKey(name)) {
      LOG.warn("Sink "+ name +" already exists!");
      return sink;
    }
    allSinks.put(name, sink);
    if (config != null) {
      registerSink(name, description, sink);
    }
    // We want to re-register the sink to pick up new config
    // when the metrics system restarts.
    register(name, new AbstractCallback() {
      @Override public void postStart() {
        register(name, description, sink);
      }
    });
    return sink;
  }


  //注册sink
  synchronized void registerSink(String name, String desc, MetricsSink sink) {
    checkNotNull(config, "config");
    MetricsConfig conf = sinkConfigs.get(name);
    MetricsSinkAdapter sa = conf != null
        ? newSink(name, desc, sink, conf)
        : newSink(name, desc, sink, config.subset(SINK_KEY));
    sinks.put(name, sa);
    sa.start();
    LOG.info("Registered sink "+ name);
  }


  // 注册回调函数
  @Override
  public synchronized void register(final Callback callback) {
    callbacks.add((Callback) getProxyForCallback(callback));
  }

  private synchronized void register(String name, final Callback callback) {
    namedCallbacks.put(name, (Callback) getProxyForCallback(callback));
  }

  private Object getProxyForCallback(final Callback callback) {
    return Proxy.newProxyInstance(callback.getClass().getClassLoader(),
        new Class<?>[] { Callback.class }, new InvocationHandler() {
          @Override
          public Object invoke(Object proxy, Method method, Object[] args)
              throws Throwable {
            try {
              return method.invoke(callback, args);
            }
            catch (Exception e) {
              // These are not considered fatal.
              LOG.warn("Caught exception in callback " + method.getName(), e);
            }
            return null;
          }
        });
  }

  //启动 Mbean
  @Override
  public synchronized void startMetricsMBeans() {
    for (MetricsSourceAdapter sa : sources.values()) {
      sa.startMBeans();
    }
  }

  @Override
  public synchronized void stopMetricsMBeans() {
    for (MetricsSourceAdapter sa : sources.values()) {
      sa.stopMBeans();
    }
  }

  @Override
  public synchronized String currentConfig() {
    PropertiesConfiguration saver = new PropertiesConfiguration();
    StringWriter writer = new StringWriter();
    saver.copy(config);
    try { saver.save(writer); }
    catch (Exception e) {
      throw new MetricsConfigException("Error stringify config", e);
    }
    return writer.toString();
  }

  //启动一个新的 Timer ,并且固定频率调用 onTimerEvent
  private synchronized void startTimer() {
    if (timer != null) {
      LOG.warn(prefix +" metrics system timer already started!");
      return;
    }
    logicalTime = 0;
    long millis = period * 1000;
    timer = new Timer("Timer for '"+ prefix +"' metrics system", true);
    timer.scheduleAtFixedRate(new TimerTask() {
          public void run() {
            try {
              onTimerEvent();
            }
            catch (Exception e) {
              LOG.warn(e);
            }
          }
        }, millis, millis);
    LOG.info("Scheduled snapshot period at "+ period +" second(s).");
  }

  //定时采样发布metrics
  synchronized void onTimerEvent() {
    logicalTime += period;
    if (sinks.size() > 0) {
      publishMetrics(sampleMetrics(), false);
    }
  }
  
  /**
   * Requests an immediate publish of all metrics from sources to sinks.
   */
  @Override
  public void publishMetricsNow() {
    if (sinks.size() > 0) {
      publishMetrics(sampleMetrics(), true);
    }    
  }


  //采样所有的 source 的 snapshot
  // MetricsCollector 全局唯一的采集器
  //1.sa.getMetrics 调用用户定义的getMetrics ，添加record，然后迭代 collector 中的 所有 recorderBuilder 获取 recorders,
  //把获取到的records add 进新建的 bufferBuilder 中
  //bufferBuilder has many buffer.Entry  Entry has many metricsRecords
  /**
   * Sample all the sources for a snapshot of metrics/tags
   * @return  the metrics buffer containing the snapshot
   */
  synchronized MetricsBuffer sampleMetrics() {
    collector.clear();
    // MetricsBufferBuilder 是一个list 构建并add 一个 MetricsBuffer
    //MetricsBuffer 是 MetricsRecordImpl 的可迭代容器
    MetricsBufferBuilder bufferBuilder = new MetricsBufferBuilder();

    for (Entry<String, MetricsSourceAdapter> entry : sources.entrySet()) {
      if (sourceFilter == null || sourceFilter.accepts(entry.getKey())) {
        //
        snapshotMetrics(entry.getValue(), bufferBuilder);
      }
    }
    if (publishSelfMetrics) {
      //buffer 中add 了 recorders, bufferBuilder 中add 了 buffer
      // bufferBuilder.add(new Buffer(MetricRecords))
      //
      snapshotMetrics(sysSource, bufferBuilder);
    }
    //get 的时候new 了一个 MetricsBuffer, MetricsBuffer 自身就是Entrys 的集合
    MetricsBuffer buffer = bufferBuilder.get();
    return buffer;
  }


  //对全局的metrics做一次处理，然后清空 MetricsCollector
  // MetricsCollector 是全局的 recorderBuilder 的一个容器，recorderBuilder 是 metrics 的一个容器
  // MetricsCollector getRecords 就是迭代其所有的  recorderBuilder ，调用 recorderBuilder 的 getRcorder ( getRcorder 内部会把builder的所有metrics 拿来构造一个 MetricRecorder)
  private void snapshotMetrics(MetricsSourceAdapter sa,
                               MetricsBufferBuilder bufferBuilder) {
    long startTime = Time.now();
    // 从 sourceAdapter 的 getMetrics 获取到source的metric 装配成的 MetricRecordImpl 数组
    // 然后把这些 MetricRecords 构造一个 MetricsBuffer 容器，然后添加到 MetricsBufferBuilder 中(实际是一个ArrayList) 容器中
    // MetricsBuffer 是一个 不可变的可迭代容器。容器的数据类型是 buffer 中静态类定义的entry,entry 中存的就是 iterable 的records MetricsRecordImpl


    // MetricsCollector.getRecords
    //bufferBuilder ArrayList[].add(
    //  sourceAdapter.getMetrics(
    //      source.getMetrics( 用户实现部分
    //        MetricsCollector.addRecord，返回一个 RecorderBuilder
    //          RecorderBuilder.addGauge (add to  List<AbstractMetric> metrics))
    //      MetricsCollector.getRecords(recorderBuilder.foreach(rb.getRcorder(new MetricRecorder(rb.metrics))))

    // bufferBuilder.add 就是添加一组  MetricsCollector 拿到的 MetricRecorders
    bufferBuilder.add(sa.name(), sa.getMetrics(collector, true));
    collector.clear();
    snapshotStat.add(Time.now() - startTime);
    LOG.debug("Snapshotted source "+ sa.name());
  }



  //迭代所有的的 SinkAdapter，向对应的 SinkAdapter 中 put metrics buffer（metric Records 数据）
  // MetricsBuffer 中又一个MetricsBuffer.Entry 的容器，Entry 中则是 MetricRecord 的容器
  /**
   * Publish a metrics snapshot to all the sinks
   * @param buffer  the metrics snapshot to publish
   * @param immediate  indicates that we should publish metrics immediately
   *                   instead of using a separate thread.
   */
  synchronized void publishMetrics(MetricsBuffer buffer, boolean immediate) {
    int dropped = 0;
    for (MetricsSinkAdapter sa : sinks.values()) {
      long startTime = Time.now();
      boolean result;
      if (immediate) {
        // 把 MetricsBuffer 扔到 各个 MetricsSinkAdapter 的 SinkQueue 中等待消费
        // 把 MetricsBuffer 的数据结构: entrys[entry[records],....]
        ////如果在 规定时间内  buffer 没被消费，返回false
        result = sa.putMetricsImmediate(buffer); 
      } else {
        // 队列满了 返回false
        result = sa.putMetrics(buffer, logicalTime);
      }
      dropped += result ? 0 : 1;
      publishStat.add(Time.now() - startTime);
    }
    // 消费失败增加
    droppedPubAll.incr(dropped);
  }

  private synchronized void stopTimer() {
    if (timer == null) {
      LOG.warn(prefix +" metrics system timer already stopped!");
      return;
    }
    timer.cancel();
    timer = null;
  }

  private synchronized void stopSources() {
    for (Entry<String, MetricsSourceAdapter> entry : sources.entrySet()) {
      MetricsSourceAdapter sa = entry.getValue();
      LOG.debug("Stopping metrics source "+ entry.getKey() +
          ": class=" + sa.source().getClass());
      sa.stop();
    }
    sysSource.stop();
    sources.clear();
  }

  private synchronized void stopSinks() {
    for (Entry<String, MetricsSinkAdapter> entry : sinks.entrySet()) {
      MetricsSinkAdapter sa = entry.getValue();
      LOG.debug("Stopping metrics sink "+ entry.getKey() +
          ": class=" + sa.sink().getClass());
      sa.stop();
    }
    sinks.clear();
  }

  private synchronized void configure(String prefix) {
    config = MetricsConfig.create(prefix);
    configureSinks();
    configureSources();
    configureSystem();
  }

  private synchronized void configureSystem() {
    injectedTags.add(Interns.tag(MsInfo.Hostname, getHostname()));
  }

  private synchronized void configureSinks() {
    sinkConfigs = config.getInstanceConfigs(SINK_KEY);
    int confPeriod = 0;
    for (Entry<String, MetricsConfig> entry : sinkConfigs.entrySet()) {
      MetricsConfig conf = entry.getValue();
      int sinkPeriod = conf.getInt(PERIOD_KEY, PERIOD_DEFAULT);
      confPeriod = confPeriod == 0 ? sinkPeriod
                                   : ArithmeticUtils.gcd(confPeriod, sinkPeriod);
      String clsName = conf.getClassName("");
      if (clsName == null) continue;  // sink can be registered later on
      String sinkName = entry.getKey();
      try {
        MetricsSinkAdapter sa = newSink(sinkName,
            conf.getString(DESC_KEY, sinkName), conf);
        sa.start();
        sinks.put(sinkName, sa);
      }
      catch (Exception e) {
        LOG.warn("Error creating sink '"+ sinkName +"'", e);
      }
    }
    period = confPeriod > 0 ? confPeriod
                            : config.getInt(PERIOD_KEY, PERIOD_DEFAULT);
  }

  static MetricsSinkAdapter newSink(String name, String desc, MetricsSink sink,
                                    MetricsConfig conf) {
    return new MetricsSinkAdapter(name, desc, sink, conf.getString(CONTEXT_KEY),
        conf.getFilter(SOURCE_FILTER_KEY),
        conf.getFilter(RECORD_FILTER_KEY),
        conf.getFilter(METRIC_FILTER_KEY),
        conf.getInt(PERIOD_KEY, PERIOD_DEFAULT),
        conf.getInt(QUEUE_CAPACITY_KEY, QUEUE_CAPACITY_DEFAULT),
        conf.getInt(RETRY_DELAY_KEY, RETRY_DELAY_DEFAULT),
        conf.getFloat(RETRY_BACKOFF_KEY, RETRY_BACKOFF_DEFAULT),
        conf.getInt(RETRY_COUNT_KEY, RETRY_COUNT_DEFAULT));
  }

  static MetricsSinkAdapter newSink(String name, String desc,
                                    MetricsConfig conf) {
    return newSink(name, desc, (MetricsSink) conf.getPlugin(""), conf);
  }

  private void configureSources() {
    sourceFilter = config.getFilter(PREFIX_DEFAULT + SOURCE_FILTER_KEY);
    sourceConfigs = config.getInstanceConfigs(SOURCE_KEY);
    registerSystemSource();
  }

  private void clearConfigs() {
    sinkConfigs.clear();
    sourceConfigs.clear();
    injectedTags.clear();
    config = null;
  }

  static String getHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    }
    catch (Exception e) {
      LOG.error("Error getting localhost name. Using 'localhost'...", e);
    }
    return "localhost";
  }


  // 注册 MetricsSystem 系统的 metrics
  // MetricsAnnotations.makeSource(this) 内部获取到注解工厂 MutableMetricsFactory,
  // MutableMetricsFactory 会迭代所有的注释，把对应的 metrics gauge 等都注册到  MetricsRegistry 的 metricsMap 中去
  //
  private void registerSystemSource() {
    MetricsConfig sysConf = sourceConfigs.get(MS_NAME);
    sysSource = new MetricsSourceAdapter(prefix, MS_STATS_NAME, MS_STATS_DESC,
        MetricsAnnotations.makeSource(this), injectedTags, period,
        sysConf == null ? config.subset(SOURCE_KEY) : sysConf);
    sysSource.start();
  }


  //MetricsRecordBuilder 是一个 metrics 构造器，构造不同数值，采集类型的metrics 并且添加到自身的数组容器中，简化调用
  // 这里是 MetricsSystemImpl 自己的统计信息包括什么 sink source 的统计信息
  @Override
  public synchronized void getMetrics(MetricsCollector builder, boolean all) {
    MetricsRecordBuilder rb = builder.addRecord(MS_NAME)
        .addGauge(MsInfo.NumActiveSources, sources.size())
        .addGauge(MsInfo.NumAllSources, allSources.size())
        .addGauge(MsInfo.NumActiveSinks, sinks.size())
        .addGauge(MsInfo.NumAllSinks, allSinks.size());

    for (MetricsSinkAdapter sa : sinks.values()) {
      //调用了 MetricsRegistry registry.snapshot(rb, all);
      //
      sa.snapshot(rb, all);
    }
    registry.snapshot(rb, all);
  }

  private void initSystemMBean() {
    checkNotNull(prefix, "prefix should not be null here!");
    if (mbeanName == null) {
      mbeanName = MBeans.register(prefix, MS_CONTROL_NAME, this);
    }
  }

  @Override
  public synchronized boolean shutdown() {
    LOG.debug("refCount="+ refCount);
    if (refCount <= 0) {
      LOG.debug("Redundant shutdown", new Throwable());
      return true; // already shutdown
    }
    if (--refCount > 0) return false;
    if (monitoring) {
      try { stop(); }
      catch (Exception e) {
        LOG.warn("Error stopping the metrics system", e);
      }
    }
    allSources.clear();
    allSinks.clear();
    callbacks.clear();
    namedCallbacks.clear();
    if (mbeanName != null) {
      MBeans.unregister(mbeanName);
      mbeanName = null;
    }
    LOG.info(prefix +" metrics system shutdown complete.");
    return true;
  }

  public MetricsSource getSource(String name) {
    return allSources.get(name);
  }

  @VisibleForTesting
  MetricsSourceAdapter getSourceAdapter(String name) {
    return sources.get(name);
  }

  private InitMode initMode() {
    LOG.debug("from system property: "+ System.getProperty(MS_INIT_MODE_KEY));
    LOG.debug("from environment variable: "+ System.getenv(MS_INIT_MODE_KEY));
    String m = System.getProperty(MS_INIT_MODE_KEY);
    String m2 = m == null ? System.getenv(MS_INIT_MODE_KEY) : m;
    return InitMode.valueOf((m2 == null ? InitMode.NORMAL.name() : m2)
                            .toUpperCase(Locale.US));
  }
}
