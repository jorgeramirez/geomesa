/***********************************************************************
 * Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.index.index.legacy

import java.nio.charset.StandardCharsets
import java.util.Date

import org.joda.time.{DateTime, DateTimeZone}
import org.locationtech.geomesa.filter.{FilterHelper, FilterValues}
import org.locationtech.geomesa.index.api.WrappedFeature
import org.locationtech.geomesa.index.geotools.GeoMesaDataStore
import org.locationtech.geomesa.index.index.{AttributeIndex, IndexKeySpace}
import org.locationtech.geomesa.index.utils.Explainer
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter

/**
  * Attribute plus date composite index
  */
trait AttributeDateIndex[DS <: GeoMesaDataStore[DS, F, W], F <: WrappedFeature, W, R]
    extends AttributeIndex[DS, F, W, R] {

  import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType
  import AttributeIndex._

  private val MinDateTime = new DateTime(0, 1, 1, 0, 0, 0, DateTimeZone.UTC)
  private val MaxDateTime = new DateTime(9999, 12, 31, 23, 59, 59, DateTimeZone.UTC)

  override protected def secondaryIndex(sft: SimpleFeatureType): Option[IndexKeySpace[_]] =
    Some(DateIndexKeySpace).filter(_.supports(sft))

  object DateIndexKeySpace extends IndexKeySpace[Unit] {

    override def supports(sft: SimpleFeatureType): Boolean = sft.getDtgField.isDefined

    override val indexKeyLength: Int = 12

    override def toIndexKey(sft: SimpleFeatureType, lenient: Boolean): (SimpleFeature) => Array[Byte] = {
      val dtgIndex = sft.getDtgIndex.getOrElse(-1)
      (feature) => {
        val dtg = feature.getAttribute(dtgIndex).asInstanceOf[Date]
        timeToBytes(if (dtg == null) { 0L } else { dtg.getTime })
      }
    }

    override def getRanges(sft: SimpleFeatureType,
                           filter: Filter,
                           explain: Explainer): Iterator[(Array[Byte], Array[Byte])] = {
      val intervals = sft.getDtgField.map(FilterHelper.extractIntervals(filter, _)).getOrElse(FilterValues.empty)
      intervals.values.iterator.map { bounds =>
        (timeToBytes(bounds.lower.value.getOrElse(MinDateTime).getMillis),
            roundUpTime(timeToBytes(bounds.upper.value.getOrElse(MaxDateTime).getMillis)))
      }
    }

    // store the first 12 hex chars of the time - that is roughly down to the minute interval
    private def timeToBytes(t: Long): Array[Byte] =
      typeRegistry.encode(t).substring(0, 12).getBytes(StandardCharsets.UTF_8)

    // rounds up the time to ensure our range covers all possible times given our time resolution
    private def roundUpTime(time: Array[Byte]): Array[Byte] = {
      // find the last byte in the array that is not 0xff
      var changeIndex: Int = time.length - 1
      while (changeIndex > -1 && time(changeIndex) == 0xff.toByte) { changeIndex -= 1 }

      if (changeIndex < 0) {
        // the array is all 1s - it's already at time max given our resolution
        time
      } else {
        // increment the selected byte
        time.updated(changeIndex, (time(changeIndex) + 1).asInstanceOf[Byte])
      }
    }
  }
}
