package is.hail.expr.types

import is.hail.annotations._
import is.hail.check.Gen
import is.hail.expr.ordering.ExtendedOrdering
import is.hail.utils._
import org.apache.spark.sql.Row
import org.json4s.jackson.JsonMethods

import scala.reflect.{ClassTag, classTag}

object TBaseStruct {
  /**
    * Define an ordering on Row objects. Works with any row r such that the list
    * of types of r is a prefix of types, or types is a prefix of the list of
    * types of r.
    */
  def getOrdering(types: Array[Type]): ExtendedOrdering =
    ExtendedOrdering.rowOrdering(types.map(_.ordering))

  def getMissingness(types: Array[Type], missingIdx: Array[Int]): Int = {
    assert(missingIdx.length == types.length)
    var i = 0
    types.zipWithIndex.foreach { case (t, idx) =>
      missingIdx(idx) = i
      if (!t.required)
        i += 1
    }
    i
  }

  def getByteSizeAndOffsets(types: Array[Type], nMissingBytes: Long, byteOffsets: Array[Long]): Long = {
    assert(byteOffsets.length == types.length)
    val bp = new BytePacker()

    var offset: Long = nMissingBytes
    types.zipWithIndex.foreach { case (t, i) =>
      val fSize = t.byteSize
      val fAlignment = t.alignment

      bp.getSpace(fSize, fAlignment) match {
        case Some(start) =>
          byteOffsets(i) = start
        case None =>
          val mod = offset % fAlignment
          if (mod != 0) {
            val shift = fAlignment - mod
            bp.insertSpace(shift, offset)
            offset += (fAlignment - mod)
          }
          byteOffsets(i) = offset
          offset += fSize
      }
    }
    offset
  }

  def alignment(types: Array[Type]): Long = {
    if (types.isEmpty)
      1
    else
      types.map(_.alignment).max
  }
}

abstract class TBaseStruct extends Type {
  def types: Array[Type]

  def fields: IndexedSeq[Field]
  
  def fieldRequired: Array[Boolean]

  override def children: Seq[Type] = types

  def size: Int

  def _toPretty: String = {
    val sb = new StringBuilder
    _pretty(sb, 0, compact = true)
    sb.result()
  }

  override def _typeCheck(a: Any): Boolean = a match {
    case row: Row =>
      row.length == types.length &&
        isComparableAt(a)
    case _ => false
  }

  def relaxedTypeCheck(a: Any): Boolean = a match {
    case row: Row =>
      row.length <= types.length &&
        isComparableAt(a)
    case _ => false
  }

  def isComparableAt(a: Annotation): Boolean = a match {
    case row: Row =>
      row.toSeq.zip(types).forall {
        case (v, t) => t.typeCheck(v)
      }
    case _ => false
  }

  def isIsomorphicTo(other: TBaseStruct): Boolean =
    size == other.size && isCompatibleWith(other)

  def isPrefixOf(other: TBaseStruct): Boolean =
    size <= other.size && isCompatibleWith(other)

  def isCompatibleWith(other: TBaseStruct): Boolean =
    fields.zip(other.fields).forall{ case (l, r) => l.typ isOfType r.typ }

  def truncate(newSize: Int): TBaseStruct

  override def str(a: Annotation): String = JsonMethods.compact(toJSON(a))

  override def genNonmissingValue: Gen[Annotation] = {
    if (types.isEmpty) {
      Gen.const(Annotation.empty)
    } else
      Gen.size.flatMap(fuel =>
        if (types.length > fuel)
          Gen.uniformSequence(types.map(t => if (t.required) t.genValue else Gen.const(null))).map(a => Annotation(a: _*))
        else
          Gen.uniformSequence(types.map(t => t.genValue)).map(a => Annotation(a: _*)))
  }

  override def valuesSimilar(a1: Annotation, a2: Annotation, tolerance: Double, absolute: Boolean): Boolean =
    a1 == a2 || (a1 != null && a2 != null
      && types.zip(a1.asInstanceOf[Row].toSeq).zip(a2.asInstanceOf[Row].toSeq)
      .forall {
        case ((t, x1), x2) =>
          t.valuesSimilar(x1, x2, tolerance, absolute)
      })

  override def scalaClassTag: ClassTag[Row] = classTag[Row]
}
