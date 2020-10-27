package is.hail.types.physical.stypes

import is.hail.annotations.{CodeOrdering, Region}
import is.hail.asm4s.{Code, IntInfo, LongInfo, Settable, SettableBuilder, TypeInfo, Value}
import is.hail.expr.ir.{EmitCodeBuilder, EmitMethodBuilder, IEmitCode, SortOrder}
import is.hail.types.physical.{PArray, PCode, PContainer, PIndexableCode, PIndexableValue, PSettable, PType}
import is.hail.utils.FastIndexedSeq

trait SContainer extends SType

case class SIndexablePointer(pType: PContainer) extends SContainer {
  def codeOrdering(mb: EmitMethodBuilder[_], other: SType, so: SortOrder): CodeOrdering = pType.codeOrdering(mb, other.pType, so)

  def coerceOrCopy(cb: EmitCodeBuilder, region: Value[Region], value: PCode, deepCopy: Boolean): PCode = {
    value.st match {
      case SIndexablePointer(pt2) if pt2.equalModuloRequired(pType) && !deepCopy =>
        value
      case _ =>
        new SIndexablePointerCode(this, pType.store(cb, region, value, deepCopy))
    }
  }

  def codeTupleTypes(): IndexedSeq[TypeInfo[_]] = FastIndexedSeq(LongInfo, IntInfo, LongInfo)

  def loadFrom(cb: EmitCodeBuilder, region: Value[Region], pt: PType, addr: Code[Long]): PCode = {
    if (pt == this.pType)
      new SIndexablePointerCode(this, addr)
    else
      coerceOrCopy(cb, region, pt.getPointerTo(cb, addr), deepCopy = false)
  }
}


class SIndexablePointerCode(val st: SIndexablePointer, val a: Code[Long]) extends PIndexableCode {
  val pt: PContainer = st.pType

  def code: Code[_] = a

  def codeTuple(): IndexedSeq[Code[_]] = FastIndexedSeq(a)

  def loadLength(): Code[Int] = pt.loadLength(a)

  def memoize(cb: EmitCodeBuilder, name: String, sb: SettableBuilder): PIndexableValue = {
    val s = SIndexablePointerSettable(sb, st, name)
    cb.assign(s, this)
    s
  }

  def memoize(cb: EmitCodeBuilder, name: String): PIndexableValue = memoize(cb, name, cb.localBuilder)

  def memoizeField(cb: EmitCodeBuilder, name: String): PIndexableValue = memoize(cb, name, cb.fieldBuilder)

  def store(mb: EmitMethodBuilder[_], r: Value[Region], dst: Code[Long]): Code[Unit] = {
    EmitCodeBuilder.scopedVoid(mb) {cb =>
      pt.storeAtAddress(cb, dst, r, this, false)
    }
  }
}

object SIndexablePointerSettable {
  def apply(sb: SettableBuilder, st: SIndexablePointer, name: String): SIndexablePointerSettable = {
    new SIndexablePointerSettable(st,
      sb.newSettable[Long](s"${ name }_a"),
      sb.newSettable[Int](s"${ name }_length"),
      sb.newSettable[Long](s"${ name }_elems_addr"))
  }
}

class SIndexablePointerSettable(
  val st: SIndexablePointer,
  val a: Settable[Long],
  val length: Settable[Int],
  val elementsAddress: Settable[Long]
) extends PIndexableValue with PSettable {
  val pt: PContainer = st.pType

  def get: PIndexableCode = new SIndexablePointerCode(st, a)

  def settableTuple(): IndexedSeq[Settable[_]] = FastIndexedSeq(a, length, elementsAddress)

  def loadLength(): Value[Int] = length

  def loadElement(cb: EmitCodeBuilder, i: Code[Int]): IEmitCode = {
    val iv = cb.newLocal("pcindval_i", i)
    IEmitCode(cb,
      isElementMissing(iv),
      pt.elementType.getPointerTo(cb, pt.loadElement(a, length, i))) // FIXME loadElement should take elementsAddress
  }

  def isElementMissing(i: Code[Int]): Code[Boolean] = pt.isElementMissing(a, i)

  def store(cb: EmitCodeBuilder, pc: PCode): Unit = {
    cb.assign(a, pc.asInstanceOf[SIndexablePointerCode].a)
    cb.assign(length, pt.loadLength(a))
    cb.assign(elementsAddress, pt.firstElementOffset(a, length))
  }
}
