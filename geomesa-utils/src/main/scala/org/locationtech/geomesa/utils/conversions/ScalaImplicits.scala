/***********************************************************************
 * Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.utils.conversions

import scala.collection.TraversableLike

object ScalaImplicits {

  implicit class RichTraversableOnce[T](val seq: TraversableOnce[T]) extends AnyVal {
    def minOption(implicit cmp: Ordering[T]): Option[T] = if (seq.isEmpty) None else Some(seq.min)
    def minOrElse(or: T)(implicit cmp: Ordering[T]): T = if (seq.isEmpty) or else seq.min

    def maxOption(implicit cmp: Ordering[T]): Option[T] = if (seq.isEmpty) None else Some(seq.max)
    def maxOrElse(or: T)(implicit cmp: Ordering[T]): T = if (seq.isEmpty) or else seq.max

    def sumOption(implicit num: Numeric[T]): Option[T] = if (seq.isEmpty) None else Some(seq.sum)
    def sumOrElse(or: T)(implicit num: Numeric[T]): T = if (seq.isEmpty) or else seq.sum

    def foreachIndex[U](f: (T, Int) => U): Unit = {
      var i = 0
      seq.foreach { v => f(v, i); i += 1 }
    }
  }

  implicit class RichTraversableLike[+A, +Repr](val seq: TraversableLike[A, Repr]) extends AnyVal {
    def tailOption: Repr = if (seq.isEmpty) seq.asInstanceOf[Repr] else seq.tail
  }

  implicit class RichIterator[T](val iter: Iterator[T]) extends AnyVal {
    def head: T = iter.next()
    def headOption: Option[T] = iter.find(_ != null)
  }

  object TieredOrdering {
    // noinspection LanguageFeature
    implicit def toTieredOrdering[T](orderings: Seq[Ordering[T]]): Ordering[T] = TieredOrdering(orderings)
  }

  case class TieredOrdering[T](orderings: Seq[Ordering[T]]) extends Ordering[T] {
    override def compare(x: T, y: T): Int = {
      orderings.foreach { o =>
        val i = o.compare(x, y)
        if (i != 0) {
          return i
        }
      }
      0
    }
  }
}
