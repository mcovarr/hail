package is.hail.types.physical.stypes

import is.hail.annotations.{CodeOrdering, Region}
import is.hail.asm4s.{Code, IntInfo, LongInfo, Settable, SettableBuilder, TypeInfo, Value}
import is.hail.expr.ir.{EmitCodeBuilder, EmitMethodBuilder, IEmitCode, SortOrder}
import is.hail.types.physical.{PCanonicalInterval, PCode, PInterval, PIntervalCode, PIntervalValue, PSettable, PType}
import is.hail.utils.FastIndexedSeq

trait SInterval extends SType

case class SIntervalPointer(pType: PInterval) extends SInterval {


  def codeOrdering(mb: EmitMethodBuilder[_], other: SType, so: SortOrder): CodeOrdering = pType.codeOrdering(mb, other.pType, so)

  def coerceOrCopy(cb: EmitCodeBuilder, region: Value[Region], value: PCode, deepCopy: Boolean): PCode = {
    value.st match {
      case SIntervalPointer(pt) if pt.equalModuloRequired(this.pType) && !deepCopy =>
        value
      case _ =>
        new SIntervalPointerCode(this, pType.store(cb, region, value, deepCopy))

    }
  }

  def codeTupleTypes(): IndexedSeq[TypeInfo[_]] = FastIndexedSeq(LongInfo, IntInfo, IntInfo)

  def loadFrom(cb: EmitCodeBuilder, region: Value[Region], pt: PType, addr: Code[Long]): PCode = {
    pt match {
      case t: PCanonicalInterval if t.equalModuloRequired(this.pType) =>
        new SIntervalPointerCode(this, addr)
      case _ =>
        new SIntervalPointerCode(this, pType.store(cb, region, pt.getPointerTo(cb, addr), false))
    }
  }
}


object SIntervalPointerSettable {
  def apply(sb: SettableBuilder, st: SIntervalPointer, name: String): SIntervalPointerSettable = {
    new SIntervalPointerSettable(st,
      sb.newSettable[Long](s"${ name }_a"),
      sb.newSettable[Boolean](s"${ name }_includes_start"),
      sb.newSettable[Boolean](s"${ name }_includes_end"))
  }
}

class SIntervalPointerSettable(
  val st: SIntervalPointer,
  a: Settable[Long],
  val includesStart: Settable[Boolean],
  val includesEnd: Settable[Boolean]
) extends PIntervalValue with PSettable {
  def get: PIntervalCode = new SIntervalPointerCode(st, a)

  val pt: PInterval = st.pType

  def settableTuple(): IndexedSeq[Settable[_]] = FastIndexedSeq(a, includesStart, includesEnd)

  def loadStart(cb: EmitCodeBuilder): IEmitCode =
    IEmitCode(cb,
      !(pt.startDefined(a)),
      pt.pointType.getPointerTo(cb, pt.loadStart(a)))

  def startDefined(cb: EmitCodeBuilder): Code[Boolean] = pt.startDefined(a)

  def loadEnd(cb: EmitCodeBuilder): IEmitCode =
    IEmitCode(cb,
      !(pt.endDefined(a)),
      pt.pointType.getPointerTo(cb, pt.loadEnd(a)))

  def endDefined(cb: EmitCodeBuilder): Code[Boolean] = pt.endDefined(a)

  def store(cb: EmitCodeBuilder, pc: PCode): Unit = {
    cb.assign(a, pc.asInstanceOf[SIntervalPointerCode].a)
    cb.assign(includesStart, pt.includesStart(a.load()))
    cb.assign(includesEnd, pt.includesEnd(a.load()))
  }
}

class SIntervalPointerCode(val st: SIntervalPointer, val a: Code[Long]) extends PIntervalCode {
  override def pt: PInterval = st.pType

  def code: Code[_] = a

  def codeTuple(): IndexedSeq[Code[_]] = FastIndexedSeq(a)

  def includesStart(): Code[Boolean] = pt.includesStart(a)

  def includesEnd(): Code[Boolean] = pt.includesEnd(a)

  def memoize(cb: EmitCodeBuilder, name: String, sb: SettableBuilder): PIntervalValue = {
    val s = SIntervalPointerSettable(sb, st, name)
    cb.assign(s, this)
    s
  }

  def memoize(cb: EmitCodeBuilder, name: String): PIntervalValue = memoize(cb, name, cb.localBuilder)

  def memoizeField(cb: EmitCodeBuilder, name: String): PIntervalValue = memoize(cb, name, cb.fieldBuilder)
}
