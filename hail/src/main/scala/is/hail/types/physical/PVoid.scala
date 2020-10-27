package is.hail.types.physical

import is.hail.annotations.UnsafeOrdering
import is.hail.asm4s.{Code, TypeInfo, UnitInfo}
import is.hail.expr.ir.EmitCodeBuilder
import is.hail.types.physical.stypes.{SType, SVoid}
import is.hail.types.virtual.{TVoid, Type}

case object PVoid extends PType with PUnrealizable {

  override def sType: SType = SVoid

  def virtualType: Type = TVoid

  override val required = true

  def _asIdent = "void"

  override def _pretty(sb: StringBuilder, indent: Int, compact: Boolean): Unit = sb.append("PVoid")

  def setRequired(required: Boolean) = PVoid

  override def unsafeOrdering(): UnsafeOrdering = throw new NotImplementedError()
}

