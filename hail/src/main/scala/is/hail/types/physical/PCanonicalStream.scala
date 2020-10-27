package is.hail.types.physical

import is.hail.annotations.UnsafeOrdering
import is.hail.asm4s.Code
import is.hail.expr.ir.EmitStream.SizedStream
import is.hail.types.virtual.{TStream, Type}
import is.hail.expr.ir.{EmitCode, EmitCodeBuilder, Stream}
import is.hail.types.physical.stypes.{SStream, SStreamCode, SType}

final case class PCanonicalStream(elementType: PType, separateRegions: Boolean = false, required: Boolean = false) extends PStream {
  override val fundamentalType: PStream = {
    if (elementType == elementType.fundamentalType)
      this
    else
      this.copy(elementType = elementType.fundamentalType)
  }

  override def unsafeOrdering(): UnsafeOrdering = throw new NotImplementedError()

  override def _pretty(sb: StringBuilder, indent: Int, compact: Boolean = false) {
    sb.append("PCStream[")
    elementType.pretty(sb, indent, compact)
    sb.append("]")
  }

  override def defaultValue: SStreamCode =
    SStreamCode(SStream(elementType.sType, separateRegions), SizedStream(Code._empty, _ => Stream.empty(EmitCode.missing(elementType)), Some(0)))

  override def deepRename(t: Type) = deepRenameStream(t.asInstanceOf[TStream])

  private def deepRenameStream(t: TStream): PStream =
    copy(elementType = elementType.deepRename(t.elementType))

  def setRequired(required: Boolean): PCanonicalStream = if(required == this.required) this else this.copy(required = required)

  override def sType: SStream = SStream(elementType.sType)
}
