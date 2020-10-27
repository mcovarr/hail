package is.hail.types.physical.stypes

import is.hail.annotations.{CodeOrdering, Region}
import is.hail.asm4s._
import is.hail.expr.ir.{EmitCodeBuilder, EmitMethodBuilder, SortOrder}
import is.hail.types.physical.{PCall, PCallCode, PCallValue, PCanonicalCall, PCode, PSettable, PType}
import is.hail.utils._
import is.hail.variant.Genotype

trait SCall extends SType

case object SCanonicalCall extends SCall {
  override def pType: PType = PCanonicalCall(false)

  def codeOrdering(mb: EmitMethodBuilder[_], other: SType, so: SortOrder): CodeOrdering = PCanonicalCall(false).codeOrdering(mb, other.pType, so)

  def coerceOrCopy(cb: EmitCodeBuilder, region: Value[Region], value: PCode, deepCopy: Boolean): PCode = {
    value.st match {
      case SCanonicalCall =>
        value
    }
  }

  def codeTupleTypes(): IndexedSeq[TypeInfo[_]] = FastIndexedSeq(IntInfo)

  def loadFrom(cb: EmitCodeBuilder, region: Value[Region], pt: PType, addr: Code[Long]): PCode = {
    pt match {
      case PCanonicalCall(_) =>
        new SCanonicalCallCode(Region.loadInt(addr))
    }
  }
}

object SCanonicalCallSettable {
  def apply(sb: SettableBuilder, name: String): SCanonicalCallSettable =
    new SCanonicalCallSettable(sb.newSettable[Int](s"${ name }_call"))
}

class SCanonicalCallSettable(call: Settable[Int]) extends PCallValue with PSettable {

  val pt: PCall = PCanonicalCall()

  override def store(cb: EmitCodeBuilder, v: PCode): Unit = cb.assign(call, v.asInstanceOf[SCanonicalCallCode].call)

  val st: SCanonicalCall.type = SCanonicalCall

  def get: PCallCode = new SCanonicalCallCode(call)

  def settableTuple(): IndexedSeq[Settable[_]] = FastIndexedSeq(call)

  def store(pc: PCode): Code[Unit] = call.store(pc.asInstanceOf[SCanonicalCallCode].call)

  def ploidy(): Code[Int] = get.ploidy()

  def isPhased(): Code[Boolean] = get.isPhased()

  def forEachAllele(cb: EmitCodeBuilder)(alleleCode: Value[Int] => Unit): Unit = {
    val call2 = cb.newLocal[Int]("fea_call2", call >>> 3)
    val p = cb.newLocal[Int]("fea_ploidy", ploidy())
    val j = cb.newLocal[Int]("fea_j")
    val k = cb.newLocal[Int]("fea_k")

    cb.ifx(p.ceq(2), {
      cb.ifx(call2 < Genotype.nCachedAllelePairs, {
        cb.assign(j, Code.invokeScalaObject1[Int, Int](Genotype.getClass, "cachedAlleleJ", call2))
        cb.assign(k, Code.invokeScalaObject1[Int, Int](Genotype.getClass, "cachedAlleleK", call2))
      }, {
        cb.assign(k, (Code.invokeStatic1[Math, Double, Double]("sqrt", const(8d) * call2.toD + 1d) / 2d - 0.5).toI)
        cb.assign(j, call2 - (k * (k + 1) / 2))
      })
      alleleCode(j)
      cb.ifx(isPhased(), cb.assign(k, k - j))
      alleleCode(k)
    }, {
      cb.ifx(p.ceq(1),
        alleleCode(call2),
        cb.ifx(p.cne(0),
          cb.append(Code._fatal[Unit](const("invalid ploidy: ").concat(p.toS)))))
    })
  }
}

class SCanonicalCallCode(val call: Code[Int]) extends PCallCode {

  val pt: PCall = PCanonicalCall()

  val st: SCanonicalCall.type = SCanonicalCall

  def code: Code[_] = call

  def codeTuple(): IndexedSeq[Code[_]] = FastIndexedSeq(call)

  def ploidy(): Code[Int] = (call >>> 1) & 0x3

  def isPhased(): Code[Boolean] = (call & 0x1).ceq(1)

  def memoize(cb: EmitCodeBuilder, name: String, sb: SettableBuilder): PCallValue = {
    val s = SCanonicalCallSettable(sb, name)
    cb.assign(s, this)
    s
  }

  def memoize(cb: EmitCodeBuilder, name: String): PCallValue = memoize(cb, name, cb.localBuilder)

  def memoizeField(cb: EmitCodeBuilder, name: String): PCallValue = memoize(cb, name, cb.fieldBuilder)

  def store(mb: EmitMethodBuilder[_], r: Value[Region], dst: Code[Long]): Code[Unit] = Region.storeInt(dst, call)
}
