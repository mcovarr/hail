package is.hail.types.virtual

import is.hail.annotations.{Annotation, ExtendedOrdering}
import is.hail.check.Gen
import is.hail.types.physical.PInterval
import is.hail.utils.{FastSeq, Interval}

import scala.reflect.{ClassTag, classTag}

case class TInterval(pointType: Type) extends Type {
  override def children = FastSeq(pointType)

  def _toPretty = s"""Interval[$pointType]"""

  override def pyString(sb: StringBuilder): Unit = {
    sb.append("interval<")
    pointType.pyString(sb)
    sb.append('>')
  }
  override def _pretty(sb: StringBuilder, indent: Int, compact: Boolean = false) {
    sb.append("Interval[")
    pointType.pretty(sb, indent, compact)
    sb.append("]")
  }

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[Interval] && {
    val i = a.asInstanceOf[Interval]
    pointType.typeCheck(i.start) && pointType.typeCheck(i.end)
  }

  override def genNonmissingValue: Gen[Annotation] = Interval.gen(pointType.ordering, pointType.genValue)

  override def scalaClassTag: ClassTag[Interval] = classTag[Interval]

  override lazy val ordering: ExtendedOrdering = mkOrdering()

  override def mkOrdering(missingEqual: Boolean): ExtendedOrdering =
    Interval.ordering(pointType.ordering, startPrimary=true, missingEqual)

  lazy val structRepresentation: TStruct = {
    TStruct(
      "start" -> pointType,
      "end" -> pointType,
      "includesStart" -> TBoolean,
      "includesEnd" -> TBoolean)
  }

  override def unify(concrete: Type): Boolean = concrete match {
    case TInterval(cpointType) => pointType.unify(cpointType)
    case _ => false
  }

  override def subst() = TInterval(pointType.subst())
}
