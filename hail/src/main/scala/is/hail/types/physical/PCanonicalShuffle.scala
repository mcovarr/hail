package is.hail.types.physical

import is.hail.annotations._
import is.hail.asm4s._
import is.hail.expr.ir._
import is.hail.types.physical.stypes.{SBinaryPointerCode, SCanonicalShufflePointer, SCanonicalShufflePointerCode}
import is.hail.types.virtual._

final case class PCanonicalShuffle(
  val tShuffle: TShuffle,
  required: Boolean = false
) extends PShuffle {
  def _asIdent = "shuffle"

  override def _pretty(sb: StringBuilder, indent: Int, compact: Boolean): Unit = sb.append("PCShuffle")

  val representation: PCanonicalBinary = PCanonicalBinary(required)

  def codeOrdering(mb: EmitMethodBuilder[_], other: PType): CodeOrdering = {
    assert(other isOfType this)
    representation.codeOrdering(mb)
  }

  def setRequired(required: Boolean) = if (required == this.required) this else PCanonicalShuffle(tShuffle, required)

  def unsafeOrdering(): UnsafeOrdering = representation.unsafeOrdering()

  override def byteSize: Long = representation.byteSize

  override def alignment: Long = representation.alignment

  override def fundamentalType: PType = representation.fundamentalType

  override def encodableType: PType = representation.encodableType

  override def containsPointers: Boolean = representation.containsPointers

  def copyFromType(cb: EmitCodeBuilder, region: Value[Region], srcPType: PType, srcAddress: Code[Long], deepCopy: Boolean): Code[Long] = {
    srcPType match {
      case t@PCanonicalShuffle(_, _) =>
        representation.copyFromType(cb, region, t.representation, srcAddress, deepCopy)
    }
  }

  def _copyFromAddress(region: Region, srcPType: PType, srcAddress: Long, deepCopy: Boolean): Long = {
    srcPType match {
      case t: PCanonicalShuffle =>
        representation.copyFromAddress(region, t.representation, srcAddress, deepCopy)
    }
  }

  def unstagedStoreAtAddress(addr: Long, region: Region, srcPType: PType, srcAddress: Long, deepCopy: Boolean): Unit =
    this.representation.unstagedStoreAtAddress(addr, region, srcPType.fundamentalType, srcAddress, deepCopy)

  def loadBinary(cb: EmitCodeBuilder, addr: Code[Long]): SBinaryPointerCode = representation.getPointerTo(cb, addr).asInstanceOf[SBinaryPointerCode]

  def sType: SCanonicalShufflePointer = SCanonicalShufflePointer(this)

  def getPointerTo(cb: EmitCodeBuilder, addr: Code[Long]): PCode = sType.loadFrom(cb, null, this, addr)

  def store(cb: EmitCodeBuilder, region: Value[Region], value: PCode, deepCopy: Boolean): Code[Long] = {
    value.st match {
      case SCanonicalShufflePointer(t) =>
        representation.store(cb, region, value.asInstanceOf[SCanonicalShufflePointerCode].shuffle, deepCopy)
    }
  }

  def storeAtAddress(cb: EmitCodeBuilder, addr: Code[Long], region: Value[Region], value: PCode, deepCopy: Boolean): Unit = {
    value.st match {
      case SCanonicalShufflePointer(t) =>
        representation.storeAtAddress(cb, addr, region, value.asInstanceOf[SCanonicalShufflePointerCode].shuffle, deepCopy)
    }

  }
}

