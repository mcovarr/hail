package is.hail.types.physical

import is.hail.annotations._
import is.hail.asm4s._
import is.hail.expr.ir.{EmitCodeBuilder, EmitMethodBuilder, IEmitCode}
import is.hail.types.physical.stypes.{SIntervalPointer, SIntervalPointerCode, SType}
import is.hail.types.virtual.{TInterval, Type}
import is.hail.utils.FastIndexedSeq

final case class PCanonicalInterval(pointType: PType, override val required: Boolean = false) extends PInterval {
  def _asIdent = s"interval_of_${ pointType.asIdent }"

  override def _pretty(sb: StringBuilder, indent: Int, compact: Boolean = false) {
    sb.append("PCInterval[")
    pointType.pretty(sb, indent, compact)
    sb.append("]")
  }

  val representation: PStruct = PCanonicalStruct(
    required,
    "start" -> pointType,
    "end" -> pointType,
    "includesStart" -> PBooleanRequired,
    "includesEnd" -> PBooleanRequired)

  def setRequired(required: Boolean) = if (required == this.required) this else PCanonicalInterval(this.pointType, required)

  def startOffset(off: Code[Long]): Code[Long] = representation.fieldOffset(off, 0)

  def endOffset(off: Code[Long]): Code[Long] = representation.fieldOffset(off, 1)

  def loadStart(off: Long): Long = representation.loadField(off, 0)

  def loadStart(off: Code[Long]): Code[Long] = representation.loadField(off, 0)

  def loadEnd(off: Long): Long = representation.loadField(off, 1)

  def loadEnd(off: Code[Long]): Code[Long] = representation.loadField(off, 1)

  def startDefined(off: Long): Boolean = representation.isFieldDefined(off, 0)

  def endDefined(off: Long): Boolean = representation.isFieldDefined(off, 1)

  def includesStart(off: Long): Boolean = Region.loadBoolean(representation.loadField(off, 2))

  def includesEnd(off: Long): Boolean = Region.loadBoolean(representation.loadField(off, 3))

  def startDefined(off: Code[Long]): Code[Boolean] = representation.isFieldDefined(off, 0)

  def endDefined(off: Code[Long]): Code[Boolean] = representation.isFieldDefined(off, 1)

  def includesStart(off: Code[Long]): Code[Boolean] =
    Region.loadBoolean(representation.loadField(off, 2))

  def includesEnd(off: Code[Long]): Code[Boolean] =
    Region.loadBoolean(representation.loadField(off, 3))

  override def deepRename(t: Type) = deepRenameInterval(t.asInstanceOf[TInterval])

  private def deepRenameInterval(t: TInterval) =
    PCanonicalInterval(this.pointType.deepRename(t.pointType), this.required)

  def containsPointers: Boolean = representation.containsPointers

  def copyFromType(cb: EmitCodeBuilder, region: Value[Region], srcPType: PType, srcAddress: Code[Long], deepCopy: Boolean): Code[Long] = {
    srcPType match {
      case t: PCanonicalInterval => representation.copyFromType(cb, region, t.representation, srcAddress, deepCopy)
    }
  }

  def encodableType: PType = representation.encodableType

  def sType: SIntervalPointer = SIntervalPointer(this)

  def getPointerTo(cb: EmitCodeBuilder, addr: Code[Long]): PCode = sType.loadFrom(cb, null, this, addr)

  def store(cb: EmitCodeBuilder, region: Value[Region], value: PCode, deepCopy: Boolean): Code[Long] = {
    value.st match {
      case SIntervalPointer(t: PCanonicalInterval) =>
        representation.store(cb, region, t.representation.getPointerTo(cb, value.asInstanceOf[SIntervalPointerCode].a), deepCopy)
    }
  }

  def storeAtAddress(cb: EmitCodeBuilder, addr: Code[Long], region: Value[Region], value: PCode, deepCopy: Boolean): Unit = {
    value.st match {
      case SIntervalPointer(t: PCanonicalInterval) =>
        representation.storeAtAddress(cb, addr, region, t.representation.getPointerTo(cb, value.asInstanceOf[SIntervalPointerCode].a), deepCopy)
    }
  }
  def unstagedStoreAtAddress(addr: Long, region: Region, srcPType: PType, srcAddress: Long, deepCopy: Boolean): Unit = {
    srcPType match {
      case t: PCanonicalInterval =>
        representation.unstagedStoreAtAddress(addr, region, t.representation, srcAddress, deepCopy)
    }
  }

  override def _copyFromAddress(region: Region, srcPType: PType, srcAddress: Long, deepCopy: Boolean): Long = {
    srcPType match {
      case t: PCanonicalInterval =>
        representation._copyFromAddress(region, t.representation, srcAddress, deepCopy)
    }
  }
}
