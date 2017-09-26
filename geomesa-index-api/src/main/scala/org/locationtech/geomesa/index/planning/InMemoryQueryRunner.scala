/***********************************************************************
 * Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.index.planning

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Date

import com.vividsolutions.jts.geom.Envelope
import org.geotools.data.Query
import org.geotools.factory.Hints
import org.locationtech.geomesa.arrow.io.DictionaryBuildingWriter
import org.locationtech.geomesa.arrow.io.records.RecordBatchUnloader
import org.locationtech.geomesa.arrow.vector.SimpleFeatureVector.SimpleFeatureEncoding
import org.locationtech.geomesa.arrow.vector.{ArrowDictionary, SimpleFeatureVector}
import org.locationtech.geomesa.arrow.{ArrowEncodedSft, ArrowProperties}
import org.locationtech.geomesa.features.{ScalaSimpleFeature, TransformSimpleFeature}
import org.locationtech.geomesa.filter.factory.FastFilterFactory
import org.locationtech.geomesa.index.iterators.{ArrowBatchScan, DensityScan}
import org.locationtech.geomesa.index.stats.GeoMesaStats
import org.locationtech.geomesa.index.utils.{Explainer, KryoLazyStatsUtils}
import org.locationtech.geomesa.security.{AuthorizationsProvider, SecurityUtils, VisibilityEvaluator}
import org.locationtech.geomesa.utils.bin.BinaryOutputEncoder
import org.locationtech.geomesa.utils.bin.BinaryOutputEncoder.EncodingOptions
import org.locationtech.geomesa.utils.collection.{CloseableIterator, SimpleFeatureOrdering}
import org.locationtech.geomesa.utils.geotools.{GeometryUtils, GridSnap}
import org.locationtech.geomesa.utils.stats.Stat
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter
import org.opengis.filter.sort.SortBy

abstract class InMemoryQueryRunner(stats: GeoMesaStats, authProvider: Option[AuthorizationsProvider])
    extends QueryRunner {

  import InMemoryQueryRunner.{authVisibilityCheck, noAuthVisibilityCheck}
  import org.locationtech.geomesa.index.conf.QueryHints.RichHints

  private val isVisible: (SimpleFeature, Seq[Array[Byte]]) => Boolean =
    if (authProvider.isDefined) { authVisibilityCheck } else { noAuthVisibilityCheck }

  protected def name: String

  /**
    * Return features for the given schema and filter. Does not need to account for visibility
    *
    * @param sft simple feature type
    * @param filter filter (will not be Filter.INCLUDE), if any
    * @return
    */
  protected def features(sft: SimpleFeatureType, filter: Option[Filter]): CloseableIterator[SimpleFeature]

  override def runQuery(sft: SimpleFeatureType, original: Query, explain: Explainer): CloseableIterator[SimpleFeature] = {
    import scala.collection.JavaConversions._

    val auths = authProvider.map(_.getAuthorizations.map(_.getBytes(StandardCharsets.UTF_8))).getOrElse(Seq.empty)

    val query = configureQuery(sft, original)
    optimizeFilter(sft, query)

    explain.pushLevel(s"$name query: '${sft.getTypeName}' ${org.locationtech.geomesa.filter.filterToString(query.getFilter)}")
    explain(s"bin[${query.getHints.isBinQuery}] arrow[${query.getHints.isArrowQuery}] " +
        s"density[${query.getHints.isDensityQuery}] stats[${query.getHints.isStatsQuery}]")
    explain(s"Transforms: ${query.getHints.getTransformDefinition.getOrElse("None")}")
    explain(s"Sort: ${Option(query.getSortBy).filter(_.nonEmpty).map(_.mkString(", ")).getOrElse("none")}")
    explain.popLevel()

    val iter = features(sft, Option(query.getFilter).filter(_ != Filter.INCLUDE)).filter(isVisible(_, auths))

    CloseableIterator(transform(iter, sft, query.getHints, query.getFilter, query.getSortBy))
  }

  override protected def optimizeFilter(sft: SimpleFeatureType, filter: Filter): Filter =
    FastFilterFactory.optimize(sft, filter)

  override protected [geomesa] def getReturnSft(sft: SimpleFeatureType, hints: Hints): SimpleFeatureType = {
    if (hints.isBinQuery) {
      BinaryOutputEncoder.BinEncodedSft
    } else if (hints.isArrowQuery) {
      org.locationtech.geomesa.arrow.ArrowEncodedSft
    } else if (hints.isDensityQuery) {
      DensityScan.DensitySft
    } else if (hints.isStatsQuery) {
      KryoLazyStatsUtils.StatsSft
    } else {
      super.getReturnSft(sft, hints)
    }
  }

  private def transform(features: Iterator[SimpleFeature],
                        sft: SimpleFeatureType,
                        hints: Hints,
                        filter: Filter,
                        sortBy: Array[SortBy]): Iterator[SimpleFeature] = {
    if (hints.isBinQuery) {
      val trackId = Option(hints.getBinTrackIdField).map(sft.indexOf)
      val geom = hints.getBinGeomField.map(sft.indexOf)
      val dtg = hints.getBinDtgField.map(sft.indexOf)
      binTransform(features, sft, trackId, geom, dtg, hints.getBinLabelField.map(sft.indexOf), hints.isBinSorting)
    } else if (hints.isArrowQuery) {
      arrowTransform(features, sft, hints, filter)
    } else if (hints.isDensityQuery) {
      val Some(envelope) = hints.getDensityEnvelope
      val Some((width, height)) = hints.getDensityBounds
      densityTransform(features, sft, envelope, width, height, hints.getDensityWeight)
    } else if (hints.isStatsQuery) {
      statsTransform(features, sft, hints.getTransform, hints.getStatsQuery, hints.isStatsEncode || hints.isSkipReduce)
    } else {
      hints.getTransform match {
        case None => noTransform(sft, features, SimpleFeatureOrdering(sft, sortBy))
        case Some((defs, tsft)) => projectionTransform(features, sft, tsft, defs, SimpleFeatureOrdering(tsft, sortBy))
      }
    }
  }

  private def binTransform(features: Iterator[SimpleFeature],
                           sft: SimpleFeatureType,
                           trackId: Option[Int],
                           geom: Option[Int],
                           dtg: Option[Int],
                           label: Option[Int],
                           sorting: Boolean): Iterator[SimpleFeature] = {
    import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType

    val encoder = BinaryOutputEncoder(sft, EncodingOptions(geom, dtg, trackId, label))
    val sf = new ScalaSimpleFeature(BinaryOutputEncoder.BinEncodedSft, "", Array(null, GeometryUtils.zeroPoint))
    val sorted = if (!sorting) { features } else {
      val i = dtg.orElse(sft.getDtgIndex).getOrElse(throw new IllegalArgumentException("Can't sort BIN features by date"))
      features.toList.sortBy(_.getAttribute(i).asInstanceOf[Date]).iterator
    }
    sorted.map { feature =>
      sf.setAttribute(BinaryOutputEncoder.BIN_ATTRIBUTE_INDEX, encoder.encode(feature))
      sf
    }
  }

  private def arrowTransform(features: Iterator[SimpleFeature],
                             sft: SimpleFeatureType,
                             hints: Hints,
                             filter: Filter): Iterator[SimpleFeature] = {
    val batchSize = hints.getArrowBatchSize.getOrElse(ArrowProperties.BatchSize.get.toInt)
    val dictionaryFields = hints.getArrowDictionaryFields
    val providedDictionaries = hints.getArrowDictionaryEncodedValues(sft)
    val encoding = SimpleFeatureEncoding.min(hints.isArrowIncludeFid)
    val sort = hints.getArrowSort

    val (transforms, arrowSft) = hints.getTransform match {
      case None =>
        val sorting = sort.map { case (field, reverse) => SimpleFeatureOrdering(sft, field, reverse) }
        (noTransform(sft, features, sorting), sft)
      case Some((definitions, tsft)) =>
        val sorting = sort.map { case (field, reverse) => SimpleFeatureOrdering(tsft, field, reverse) }
        (projectionTransform(features, sft, tsft, definitions, sorting), tsft)
    }

    if (sort.isDefined || hints.isArrowComputeDictionaries ||
        dictionaryFields.forall(providedDictionaries.contains)) {
      val dictionaries = ArrowBatchScan.createDictionaries(stats, sft, Option(filter), dictionaryFields,
        providedDictionaries, hints.isArrowCachedDictionaries)
      val arrows = arrowBatchTransform(transforms, arrowSft, encoding, dictionaries, batchSize)
      if (hints.isSkipReduce) { arrows } else {
        // note: already completely sorted
        ArrowBatchScan.reduceFeatures(arrowSft, hints, dictionaries, skipSort = true)(arrows)
      }
    } else {
      arrowFileTransform(transforms, arrowSft, encoding, dictionaryFields, batchSize)
    }
  }

  private def arrowBatchTransform(features: Iterator[SimpleFeature],
                                  sft: SimpleFeatureType,
                                  encoding: SimpleFeatureEncoding,
                                  dictionaries: Map[String, ArrowDictionary],
                                  batchSize: Int): Iterator[SimpleFeature] = {
    import org.locationtech.geomesa.arrow.allocator

    val vector = SimpleFeatureVector.create(sft, dictionaries, encoding)
    val batchWriter = new RecordBatchUnloader(vector)

    val sf = new ScalaSimpleFeature(ArrowEncodedSft, "", Array(null, GeometryUtils.zeroPoint))

    new Iterator[SimpleFeature] {
      override def hasNext: Boolean = features.hasNext
      override def next(): SimpleFeature = {
        var index = 0
        vector.clear()
        features.take(batchSize).foreach { f =>
          vector.writer.set(index, f)
          index += 1
        }
        sf.setAttribute(0, batchWriter.unload(index))
        sf
      }
    }
  }

  private def arrowFileTransform(features: Iterator[SimpleFeature],
                                 sft: SimpleFeatureType,
                                 encoding: SimpleFeatureEncoding,
                                 dictionaryFields: Seq[String],
                                 batchSize: Int): Iterator[SimpleFeature] = {
    import org.locationtech.geomesa.arrow.allocator

    val writer = DictionaryBuildingWriter.create(sft, dictionaryFields, encoding)
    val os = new ByteArrayOutputStream()

    val sf = new ScalaSimpleFeature(ArrowEncodedSft, "", Array(null, GeometryUtils.zeroPoint))

    new Iterator[SimpleFeature] {
      override def hasNext: Boolean = features.hasNext
      override def next(): SimpleFeature = {
        writer.clear()
        os.reset()
        features.take(batchSize).foreach(writer.add)
        writer.encode(os)
        sf.setAttribute(0, os.toByteArray)
        sf
      }
    }
  }

  private def densityTransform(features: Iterator[SimpleFeature],
                               sft: SimpleFeatureType,
                               envelope: Envelope,
                               width: Int,
                               height: Int,
                               weight: Option[String]): Iterator[SimpleFeature] = {
    val grid = new GridSnap(envelope, width, height)
    val result = scala.collection.mutable.Map.empty[(Int, Int), Double]
    val getWeight = DensityScan.getWeight(sft, weight)
    val writeGeom = DensityScan.writeGeometry(sft, grid)
    features.foreach(f => writeGeom(f, getWeight(f), result))

    val sf = new ScalaSimpleFeature(DensityScan.DensitySft, "", Array(GeometryUtils.zeroPoint))
    // Return value in user data so it's preserved when passed through a RetypingFeatureCollection
    sf.getUserData.put(DensityScan.DensityValueKey, DensityScan.encodeResult(result))
    Iterator(sf)
  }

  private def statsTransform(features: Iterator[SimpleFeature],
                             sft: SimpleFeatureType,
                             transform: Option[(String, SimpleFeatureType)],
                             query: String,
                             encode: Boolean): Iterator[SimpleFeature] = {
    val stat = Stat(sft, query)
    val toObserve = transform match {
      case None                => features
      case Some((tdefs, tsft)) => projectionTransform(features, sft, tsft, tdefs, None)
    }
    toObserve.foreach(stat.observe)
    val encoded = if (encode) { KryoLazyStatsUtils.encodeStat(sft)(stat) } else { stat.toJson }
    Iterator(new ScalaSimpleFeature(KryoLazyStatsUtils.StatsSft, "stat", Array(encoded, GeometryUtils.zeroPoint)))
  }

  private def projectionTransform(features: Iterator[SimpleFeature],
                                  sft: SimpleFeatureType,
                                  transform: SimpleFeatureType,
                                  definitions: String,
                                  ordering: Option[Ordering[SimpleFeature]]): Iterator[SimpleFeature] = {
    val attributes = TransformSimpleFeature.attributes(sft, transform, definitions)

    def setValues(from: SimpleFeature, to: ScalaSimpleFeature): ScalaSimpleFeature = {
      var i = 0
      while (i < attributes.length) {
        to.setAttributeNoConvert(i, attributes(i).apply(from))
        i += 1
      }
      to.setId(from.getID)
      to
    }

    ordering match {
      case None => val reusableSf = new ScalaSimpleFeature(transform, ""); features.map(setValues(_, reusableSf))
      case Some(o) => features.map(setValues(_, new ScalaSimpleFeature(transform, ""))).toList.sorted(o).iterator
    }
  }

  private def noTransform(sft: SimpleFeatureType,
                          features: Iterator[SimpleFeature],
                          ordering: Option[Ordering[SimpleFeature]]): Iterator[SimpleFeature] = {
    ordering match {
      case None    => features
      case Some(o) => features.toList.sorted(o).iterator
    }
  }
}

object InMemoryQueryRunner {

  /**
    * Used when we don't have an auth provider - any visibilities in the feature will
    * cause the check to fail, so we can skip parsing
    *
    * @param f simple feature to check
    * @param ignored not used
    * @return true if feature is visible without any authorizations, otherwise false
    */
  private def noAuthVisibilityCheck(f: SimpleFeature, ignored: Seq[Array[Byte]]): Boolean = {
    val vis = SecurityUtils.getVisibility(f)
    vis == null || vis.isEmpty
  }

  /**
    * Parses any visibilities in the feature and compares with the user's authorizations
    *
    * @param f simple feature to check
    * @param auths authorizations for the current user
    * @return true if feature is visible to the current user, otherwise false
    */
  private def authVisibilityCheck(f: SimpleFeature, auths: Seq[Array[Byte]]): Boolean = {
    val vis = SecurityUtils.getVisibility(f)
    vis == null || VisibilityEvaluator.parse(vis).evaluate(auths)
  }
}
