package is.hail.types.physical

import is.hail.annotations.{Annotation, Region, UnsafeOrdering}
import is.hail.asm4s.{Code, Value}
import is.hail.expr.ir.{EmitCodeBuilder, EmitMethodBuilder}
import is.hail.types.physical.stypes.{SCode, SValue}
import is.hail.types.physical.stypes.concrete.{SIndexablePointer, SIndexablePointerCode, SIndexablePointerValue}
import is.hail.types.physical.stypes.interfaces.SContainer

trait PArrayBackedContainer extends PContainer {
  val arrayRep: PArray

  override lazy val elementByteSize = arrayRep.elementByteSize

  override lazy val contentsAlignment = arrayRep.contentsAlignment

  override lazy val lengthHeaderBytes: Long = arrayRep.lengthHeaderBytes

  override lazy val byteSize: Long = arrayRep.byteSize

  override def loadLength(aoff: Long): Int =
    arrayRep.loadLength(aoff)

  override def loadLength(aoff: Code[Long]): Code[Int] =
    arrayRep.loadLength(aoff)

  override def storeLength(aoff: Code[Long], length: Code[Int]): Code[Unit] =
    arrayRep.storeLength(aoff, length)

  override def nMissingBytes(len: Code[Int]): Code[Int] =
    arrayRep.nMissingBytes(len)

  override def elementsOffset(length: Int): Long =
    arrayRep.elementsOffset(length)

  override def elementsOffset(length: Code[Int]): Code[Long] =
    arrayRep.elementsOffset(length)

  override def isElementDefined(aoff: Long, i: Int): Boolean =
    arrayRep.isElementDefined(aoff, i)

  override def isElementDefined(aoff: Code[Long], i: Code[Int]): Code[Boolean] =
    arrayRep.isElementDefined(aoff, i)

  override def isElementMissing(aoff: Long, i: Int): Boolean =
    arrayRep.isElementMissing(aoff, i)

  override def isElementMissing(aoff: Code[Long], i: Code[Int]): Code[Boolean] =
    arrayRep.isElementMissing(aoff, i)

  override def setElementMissing(aoff: Long, i: Int) =
    arrayRep.setElementMissing(aoff, i)

  override def setElementMissing(aoff: Code[Long], i: Code[Int]): Code[Unit] =
    arrayRep.setElementMissing(aoff, i)

  override def setElementPresent(aoff: Long, i: Int) {
      arrayRep.setElementPresent(aoff, i)
  }

  override def setElementPresent(aoff: Code[Long], i: Code[Int]): Code[Unit] =
    arrayRep.setElementPresent(aoff, i)

  override def firstElementOffset(aoff: Long, length: Int): Long =
    arrayRep.firstElementOffset(aoff, length)

  override def firstElementOffset(aoff: Code[Long], length: Code[Int]): Code[Long] =
    arrayRep.firstElementOffset(aoff, length)

  override def firstElementOffset(aoff: Code[Long]): Code[Long] =
    arrayRep.firstElementOffset(aoff)

  override def elementOffset(aoff: Long, length: Int, i: Int): Long =
    arrayRep.elementOffset(aoff, length, i)

  override def elementOffset(aoff: Long, i: Int): Long =
    arrayRep.elementOffset(aoff, loadLength(aoff), i)

  override def elementOffset(aoff: Code[Long], i: Code[Int]): Code[Long] =
    arrayRep.elementOffset(aoff, loadLength(aoff), i)

  override def elementOffset(aoff: Code[Long], length: Code[Int], i: Code[Int]): Code[Long] =
    arrayRep.elementOffset(aoff, length, i)

  override def loadElement(aoff: Long, length: Int, i: Int): Long =
    arrayRep.loadElement(aoff, length, i)

  override def loadElement(aoff: Long, i: Int): Long =
    arrayRep.loadElement(aoff, loadLength(aoff), i)

  override def loadElement(aoff: Code[Long], length: Code[Int], i: Code[Int]): Code[Long] =
    arrayRep.loadElement(aoff, length, i)

  override def loadElement(aoff: Code[Long], i: Code[Int]): Code[Long] =
    arrayRep.loadElement(aoff, loadLength(aoff), i)

  override def allocate(region: Region, length: Int): Long =
    arrayRep.allocate(region, length)

  override def allocate(region: Code[Region], length: Code[Int]): Code[Long] =
    arrayRep.allocate(region, length)

  override def setAllMissingBits(aoff: Long, length: Int) =
    arrayRep.setAllMissingBits(aoff, length)

  override def clearMissingBits(aoff: Long, length: Int) =
    arrayRep.clearMissingBits(aoff, length)

  override def initialize(aoff: Long, length: Int, setMissing: Boolean = false) =
    arrayRep.initialize(aoff, length, setMissing)

  override def stagedInitialize(aoff: Code[Long], length: Code[Int], setMissing: Boolean = false): Code[Unit] =
    arrayRep.stagedInitialize(aoff, length, setMissing)

  override def zeroes(region: Region, length: Int): Long =
    arrayRep.zeroes(region, length)

  override def zeroes(mb: EmitMethodBuilder[_], region: Value[Region], length: Code[Int]): Code[Long] =
    arrayRep.zeroes(mb, region, length)

  override def forEach(mb: EmitMethodBuilder[_], aoff: Code[Long], body: Code[Long] => Code[Unit]): Code[Unit] =
    arrayRep.forEach(mb, aoff, body)

  override def hasMissingValues(sourceOffset: Code[Long]): Code[Boolean] =
    arrayRep.hasMissingValues(sourceOffset)

  override def unsafeOrdering: UnsafeOrdering =
    unsafeOrdering(this)

  override def unsafeOrdering(rightType: PType): UnsafeOrdering =
    arrayRep.unsafeOrdering(rightType)

  override def _copyFromAddress(region: Region, srcPType: PType, srcAddress: Long, deepCopy: Boolean): Long =
    arrayRep.copyFromAddress(region, srcPType.asInstanceOf[PArrayBackedContainer].arrayRep, srcAddress, deepCopy)

  override def nextElementAddress(currentOffset: Long): Long =
    arrayRep.nextElementAddress(currentOffset)

  override def nextElementAddress(currentOffset: Code[Long]): Code[Long] =
    arrayRep.nextElementAddress(currentOffset)

  override def unstagedStoreAtAddress(addr: Long, region: Region, srcPType: PType, srcAddress: Long, deepCopy: Boolean): Unit =
    arrayRep.unstagedStoreAtAddress(addr, region, srcPType.asInstanceOf[PArrayBackedContainer].arrayRep, srcAddress, deepCopy)

  override def sType: SIndexablePointer = SIndexablePointer(setRequired(false).asInstanceOf[PArrayBackedContainer])

  override def loadCheapSCode(cb: EmitCodeBuilder, addr: Code[Long]): SIndexablePointerValue =
    new SIndexablePointerCode(sType, addr).memoize(cb, "loadCheapSCode")

  override def loadCheapSCodeField(cb: EmitCodeBuilder, addr: Code[Long]): SIndexablePointerValue =
    new SIndexablePointerCode(sType, addr).memoizeField(cb, "loadCheapSCodeField")

  override def store(cb: EmitCodeBuilder, region: Value[Region], value: SCode, deepCopy: Boolean): Code[Long] = arrayRep.store(cb, region, value.asIndexable.castToArray(cb), deepCopy)

  override def storeAtAddress(cb: EmitCodeBuilder, addr: Code[Long], region: Value[Region], value: SCode, deepCopy: Boolean): Unit =
    arrayRep.storeAtAddress(cb, addr, region, value.asIndexable.castToArray(cb), deepCopy)

  override def loadFromNested(addr: Code[Long]): Code[Long] = arrayRep.loadFromNested(addr)

  override def unstagedLoadFromNested(addr: Long): Long = arrayRep.unstagedLoadFromNested(addr)

  override def unstagedStoreJavaObjectAtAddress(addr: Long, annotation: Annotation, region: Region): Unit = {
    Region.storeAddress(addr, unstagedStoreJavaObject(annotation, region))
  }
}
