/*
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

package org.apache.hadoop.metrics2.lib;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static com.google.common.base.Preconditions.*;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.metrics2.MetricsCollector;
import org.apache.hadoop.metrics2.MetricsException;
import org.apache.hadoop.metrics2.MetricsInfo;
import org.apache.hadoop.metrics2.MetricsSource;
import org.apache.hadoop.metrics2.annotation.Metric;
import org.apache.hadoop.metrics2.annotation.Metrics;
import org.apache.hadoop.util.ReflectionUtils;

/**
 * Helper class to build metrics source object from annotations
 */
@InterfaceAudience.Private
public class MetricsSourceBuilder {
  private static final Log LOG = LogFactory.getLog(MetricsSourceBuilder.class);

  private final Object source;
  private final MutableMetricsFactory factory;
  private final MetricsRegistry registry;
  private MetricsInfo info;
  private boolean hasAtMetric = false;
  private boolean hasRegistry = false;

  MetricsSourceBuilder(Object source, MutableMetricsFactory factory) {
    this.source = checkNotNull(source, "source");
    this.factory = checkNotNull(factory, "mutable metrics factory");
    Class<?> cls = source.getClass();

    //初始化一个 registry，用户定义了就拿定义的，没定义就生成一个
    registry = initRegistry(source);

    // 这里是核心，把所有注解的 MutableMetric 调用registry.add 添加到 registry 的metrics map  中，是所有注解 MutableMetric 对象的栖身之所
    for (Field field : ReflectionUtils.getDeclaredFieldsIncludingInherited(cls)) {
      add(source, field);
    }
    for (Method method : ReflectionUtils.getDeclaredMethodsIncludingInherited(cls)) {
      add(source, method);
    }
  }


  // 构造 metricsSource 时,如果是 MetricsSource 的衍生类就返回这类，如果是注解的就返回一个新的匿名MetricsSource 类，
  public MetricsSource build() {
    if (source instanceof MetricsSource) {
      if (hasAtMetric && !hasRegistry) {
        throw new MetricsException("Hybrid metrics: registry required.");
      }
      return (MetricsSource) source;
    }
    else if (!hasAtMetric) {
      throw new MetricsException("No valid @Metric annotation found.");
    }
    return new MetricsSource() {
      @Override
      public void getMetrics(MetricsCollector builder, boolean all) {
        registry.snapshot(builder.addRecord(registry.info()), all);
      }
    };
  }

  public MetricsInfo info() {
    return info;
  }

  //创建metrics,  如果注解类中没有定义  metricsRegistry 这里metricsRegistry 作为一个范围
  private MetricsRegistry initRegistry(Object source) {
    Class<?> cls = source.getClass();
    MetricsRegistry r = null;
    // Get the registry if it already exists.
    for (Field field : ReflectionUtils.getDeclaredFieldsIncludingInherited(cls)) {
      if (field.getType() != MetricsRegistry.class) continue;
      try {
        field.setAccessible(true);
        r = (MetricsRegistry) field.get(source);
        hasRegistry = r != null;
        break;
      }
      catch (Exception e) {
        LOG.warn("Error accessing field "+ field, e);
        continue;
      }
    }
    // Create a new registry according to annotation
    // 这里 info 是 MetricsInfo 的实现是注解中的 MetricsGauge  MetricsInt MetricsFloat 等具体的 metrics type
    for (Annotation annotation : cls.getAnnotations()) {
      if (annotation instanceof Metrics) {
        Metrics ma = (Metrics) annotation;
        info = factory.getInfo(cls, ma);
        if (r == null) {
          r = new MetricsRegistry(info);
        }
        // 这里 context 等价于 metrics 的 group
        r.setContext(ma.context());
      }
    }
    if (r == null) return new MetricsRegistry(cls.getSimpleName());
    return r;
  }


  // 很重要的一个方法，比如 @Metric MutableCounterInt jobsSubmitted; 对于这个注释把 MutableCounterInt 还原成 MutableMetric 对象
  // 然后使用  registry.add 方法把 metrics put 进 MetricsRegistry 的 metricsMap 中去，这样一个source 中的所有 metrics 都会进入同一个 MetricsRegistry
  private void add(Object source, Field field) {
    for (Annotation annotation : field.getAnnotations()) {
      if (!(annotation instanceof Metric)) continue;
      try {
        // skip fields already set
        field.setAccessible(true);
        if (field.get(source) != null) continue;
      }
      catch (Exception e) {
        LOG.warn("Error accessing field "+ field +" annotated with"+
                 annotation, e);
        continue;
      }

      //调用 registry.add 方法把 metrics put 进 MetricsRegistry 的 metricsMap
      MutableMetric mutable = factory.newForField(field, (Metric) annotation,
                                                  registry);
      if (mutable != null) {
        try {
          field.set(source, mutable);
          hasAtMetric = true;
        }
        catch (Exception e) {
          throw new MetricsException("Error setting field "+ field +
                                     " annotated with "+ annotation, e);
        }
      }
    }
  }

  private void add(Object source, Method method) {
    for (Annotation annotation : method.getAnnotations()) {
      if (!(annotation instanceof Metric)) continue;
      factory.newForMethod(source, method, (Metric) annotation, registry);
      hasAtMetric = true;
    }
  }
}
