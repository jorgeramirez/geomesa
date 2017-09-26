/***********************************************************************
 * Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.kafka.data

import java.util.concurrent.atomic.AtomicLong

import com.typesafe.scalalogging.LazyLogging
import org.apache.kafka.clients.producer.{Producer, ProducerRecord}
import org.geotools.data.simple.SimpleFeatureWriter
import org.geotools.factory.Hints
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.index.geotools.GeoMesaFeatureWriter
import org.locationtech.geomesa.kafka.utils.{GeoMessage, GeoMessageSerializer}
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.{Filter, Id}

object KafkaFeatureWriter {

  private val featureIds = new AtomicLong(0)

  class AppendKafkaFeatureWriter(sft: SimpleFeatureType, producer: Producer[Array[Byte], Array[Byte]])
      extends SimpleFeatureWriter with LazyLogging {

    protected val topic: String = KafkaDataStore.topic(sft)

    protected val serializer = new GeoMessageSerializer(sft)

    protected val feature = new ScalaSimpleFeature(sft, "-1")

    override def getFeatureType: SimpleFeatureType = sft

    override def hasNext: Boolean = false

    override def next(): SimpleFeature = {
      reset(featureIds.getAndIncrement().toString)
      feature
    }

    override def write(): Unit = {
      val sf = GeoMesaFeatureWriter.featureWithFid(sft, feature)
      logger.debug(s"Writing update to $topic: $sf")
      val (key, value) = serializer.serialize(GeoMessage.change(sf))
      producer.send(new ProducerRecord(topic, key, value))
    }

    override def remove(): Unit = throw new NotImplementedError()

    override def close(): Unit = {}

    protected def reset(id: String): Unit = {
      feature.setId(id)
      var i = 0
      while (i < sft.getAttributeCount) {
        feature.setAttributeNoConvert(i, null)
        i += 1
      }
      feature.getUserData.clear()
      feature
    }

    def clear(): Unit = {
      logger.debug(s"Writing clear to $topic")
      val (key, value) = serializer.serialize(GeoMessage.clear())
      producer.send(new ProducerRecord(topic, key, value))
    }
  }

  class ModifyKafkaFeatureWriter(sft: SimpleFeatureType, producer: Producer[Array[Byte], Array[Byte]], filter: Filter)
      extends AppendKafkaFeatureWriter(sft, producer) {

    import scala.collection.JavaConversions._

    private val ids: Iterator[String] = filter match {
      case ids: Id => ids.getIDs.iterator.map(_.toString)
      case _ => throw new NotImplementedError("Only modify by ID is supported")
    }

    override def hasNext: Boolean = ids.hasNext

    override def next(): SimpleFeature = {
      import org.locationtech.geomesa.utils.conversions.ScalaImplicits.RichIterator
      reset(ids.headOption.getOrElse(featureIds.getAndIncrement().toString))
      // default to using the provided fid
      feature.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
      feature
    }

    override def remove(): Unit = {
      val id = GeoMesaFeatureWriter.featureWithFid(sft, feature).getID
      logger.debug(s"Writing delete to $topic: $id")
      val (key, value) = serializer.serialize(GeoMessage.delete(id))
      producer.send(new ProducerRecord(topic, key, value))
    }
  }
}
