package is.hail.types.physical.stypes

import is.hail.annotations.{CodeOrdering, Region}
import is.hail.asm4s.{Code, TypeInfo, Value}
import is.hail.expr.ir.{EmitCodeBuilder, EmitMethodBuilder, SortOrder}
import is.hail.expr.ir.EmitStream.SizedStream
import is.hail.types.physical.{PCanonicalStream, PCode, PStream, PStreamCode, PType, PValue}

case class SStream(elementType: SType, separateRegions: Boolean = false) extends SType {
  def pType: PStream = PCanonicalStream(elementType.pType, separateRegions, false)

  override def coerceOrCopy(cb: EmitCodeBuilder, region: Value[Region], value: PCode, deepCopy: Boolean): PCode = {
    if (deepCopy) throw new UnsupportedOperationException

    assert(value.st == this)
    value
  }

  override def codeTupleTypes(): IndexedSeq[TypeInfo[_]] = throw new UnsupportedOperationException

  override def codeOrdering(mb: EmitMethodBuilder[_], other: SType, so: SortOrder): CodeOrdering = throw new UnsupportedOperationException

  override def loadFrom(cb: EmitCodeBuilder, region: Value[Region], pt: PType, addr: Code[Long]): PCode = throw new UnsupportedOperationException
}


final case class SStreamCode(st: SStream, stream: SizedStream) extends PStreamCode {
  self =>
  override def pt: PStream = st.pType

  def memoize(cb: EmitCodeBuilder, name: String): PValue = new PValue {
    def pt: PStream = PCanonicalStream(st.pType)

    override def st: SType = self.st

    var used: Boolean = false

    def get: PCode = {
      assert(!used)
      used = true
      self
    }
  }
}
