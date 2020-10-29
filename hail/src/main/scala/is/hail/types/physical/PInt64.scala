package is.hail.types.physical

import is.hail.annotations.{Region, UnsafeOrdering, _}
import is.hail.asm4s.{Code, coerce, const, _}
import is.hail.expr.ir.{EmitCodeBuilder, EmitMethodBuilder}
import is.hail.types.physical.stypes.{SInt64, SType}
import is.hail.types.virtual.TInt64

case object PInt64Optional extends PInt64(false)
case object PInt64Required extends PInt64(true)

class PInt64(override val required: Boolean) extends PNumeric with PPrimitive {
  lazy val virtualType: TInt64.type = TInt64

  def _asIdent = "int64"
  override def _pretty(sb: StringBuilder, indent: Int, compact: Boolean): Unit = sb.append("PInt64")
  override type NType = PInt64

  override def unsafeOrdering(): UnsafeOrdering = new UnsafeOrdering {
    def compare(o1: Long, o2: Long): Int = {
      java.lang.Long.compare(Region.loadLong(o1), Region.loadLong(o2))
    }
  }

  def codeOrdering(mb: EmitMethodBuilder[_], other: PType): CodeOrdering = {
    assert(other isOfType this)
    new CodeOrdering {
      type T = Long

      def compareNonnull(x: Code[T], y: Code[T]): Code[Int] =
        Code.invokeStatic2[java.lang.Long, Long, Long, Int]("compare", x, y)

      def ltNonnull(x: Code[T], y: Code[T]): Code[Boolean] = x < y

      def lteqNonnull(x: Code[T], y: Code[T]): Code[Boolean] = x <= y

      def gtNonnull(x: Code[T], y: Code[T]): Code[Boolean] = x > y

      def gteqNonnull(x: Code[T], y: Code[T]): Code[Boolean] = x >= y

      def equivNonnull(x: Code[T], y: Code[T]): Code[Boolean] = x.ceq(y)
    }
  }

  override def byteSize: Long = 8

  override def zero = coerce[PInt64](const(0L))

  override def add(a: Code[_], b: Code[_]): Code[PInt64] = {
    coerce[PInt64](coerce[Long](a) + coerce[Long](b))
  }

  override def multiply(a: Code[_], b: Code[_]): Code[PInt64] = {
    coerce[PInt64](coerce[Long](a) * coerce[Long](b))
  }

  override def sType: SType = SInt64(required)

  def storePrimitiveAtAddress(cb: EmitCodeBuilder, addr: Code[Long], value: PCode): Unit =
    cb.append(Region.storeLong(addr, value.asLong.longValue(cb)))
}

object PInt64 {
  def apply(required: Boolean = false): PInt64 = if (required) PInt64Required else PInt64Optional

  def unapply(t: PInt64): Option[Boolean] = Option(t.required)
}
