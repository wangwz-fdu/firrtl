// See LICENSE for license details.

package firrtlTests.interval

import firrtl.PrimOps.constraint2bound
import firrtl.{CircuitState, ChirrtlForm, LowFirrtlCompiler, Parser, AnnotationMap}
import firrtl.ir.{Closed, Open, Bound, IntervalType, IntWidth}
import firrtl.passes.{IsMin, IsMax, IsMul, IsAdd, IsKnown}
import scala.math.BigDecimal.RoundingMode._
import firrtl.Parser.IgnoreInfo
import firrtlTests.FirrtlFlatSpec

class IntervalMathSpec extends FirrtlFlatSpec {
  val SumPattern         = """.*output sum.*<(\d+)>.*""".r
  val ProductPattern     = """.*output product.*<(\d+)>.*""".r
  val DifferencePattern  = """.*output difference.*<(\d+)>.*""".r
  val ComparisonPattern  = """.*output (\w+).*UInt<(\d+)>.*""".r
  val ShiftLeftPattern   = """.*output shl.*<(\d+)>.*""".r
  val ShiftRightPattern  = """.*output shr.*<(\d+)>.*""".r
  val ArithAssignPattern = """\s*(\w+) <= asSInt\(bits\((\w+)\((.*)\).*\)\)\s*""".r

  val prec = 0.5

  for {
    lb1        <- Seq("[", "(")
    lv1        <- Range.Double(-1.0, 1.0, prec)
    uv1        <- if(lb1 == "[") Range.Double(lv1, 1.0, prec) else Range.Double(lv1 + prec, 1.0, prec)
    ub1        <- if (lv1 == uv1) Seq("]") else Seq("]", ")")
    bp1        <- 0 to 1
    lb2        <- Seq("[", "(")
    lv2        <- Range.Double(-1.0, 1.0, prec)
    uv2        <- if(lb2 == "[") Range.Double(lv2, 1.0, prec) else Range.Double(lv2 + prec, 1.0, prec)
    ub2        <- if (lv2 == uv2) Seq("]") else Seq("]", ")")
    bp2        <- 0 to 1
  } {
    def config = s"$lb1$lv1,$uv1$ub1.$bp1 and $lb2$lv2,$uv2$ub2.$bp2"

    s"Configuration $config" should "pass" in {

      val input =
        s"""circuit Unit :
        |  module Unit :
        |    input  in1 : Interval$lb1$lv1, $uv1$ub1.$bp1
        |    input  in2 : Interval$lb2$lv2, $uv2$ub2.$bp2
        |    output sum        : Interval
        |    output difference : Interval
        |    output product    : Interval
        |    output shl        : Interval
        |    output shr        : Interval
        |    output lt         : UInt
        |    output leq        : UInt
        |    output gt         : UInt
        |    output geq        : UInt
        |    output eq         : UInt
        |    output neq        : UInt
        |    output cat        : UInt
        |    sum        <= add(in1, in2)
        |    difference <= sub(in1, in2)
        |    product    <= mul(in1, in2)
        |    shl        <= shl(in1, 3)
        |    shr        <= shr(in1, 3)
        |    lt         <= lt(in1, in2)
        |    leq        <= leq(in1, in2)
        |    gt         <= gt(in1, in2)
        |    geq        <= geq(in1, in2)
        |    eq         <= eq(in1, in2)
        |    neq        <= lt(in1, in2)
        |    cat        <= cat(in1, in2)
        |    """.stripMargin

      val lowerer = new LowFirrtlCompiler
      val res = lowerer.compileAndEmit(CircuitState(parse(input), ChirrtlForm))
      val output = res.getEmittedCircuit.value split "\n"
      def getBound(bound: String, value: Double): IsKnown = bound match {
        case "[" => Closed(BigDecimal(value))
        case "]" => Closed(BigDecimal(value))
        case "(" => Open(BigDecimal(value))
        case ")" => Open(BigDecimal(value))
      }
      for (line <- output) {
        line match {
          case SumPattern(varWidth)     =>
            val bp = IntWidth(Math.max(bp1.toInt, bp2.toInt))
            val lv = getBound(lb1, lv1) + getBound(lb2, lv2)
            val uv = getBound(ub1, uv1) + getBound(ub2, uv2)
            assert(varWidth.toInt == IntervalType(lv, uv, bp).width.asInstanceOf[IntWidth].width)
          case ProductPattern(varWidth)     =>
            val bp = IntWidth(bp1.toInt + bp2.toInt)
            val l1 = getBound(lb1, lv1)
            val u1 = getBound(ub1, uv1)
            val l2 = getBound(lb2, lv2)
            val u2 = getBound(ub2, uv2)
            val lv = IsMin(IsMul(l1, l2), IsMul(l1, u2), IsMul(u1, l2), IsMul(u1, u2)).optimize()
            val uv = IsMax(IsMul(l1, l2), IsMul(l1, u2), IsMul(u1, l2), IsMul(u1, u2)).optimize()
            assert(varWidth.toInt == IntervalType(lv, uv, bp).width.asInstanceOf[IntWidth].width)
          case DifferencePattern(varWidth)     =>
            val bp = IntWidth(Math.max(bp1.toInt, bp2.toInt))
            val l1 = getBound(lb1, lv1)
            val u1 = getBound(ub1, uv1)
            val l2 = getBound(lb2, lv2)
            val u2 = getBound(ub2, uv2)
            val lv = l1 + u2.neg
            val uv = u1 + l2.neg
            assert(varWidth.toInt == IntervalType(lv, uv, bp).width.asInstanceOf[IntWidth].width)
          case ShiftLeftPattern(varWidth)     =>
            val bp = IntWidth(bp1.toInt)
            val lv = getBound(lb1, lv1) * Closed(3)
            val uv = getBound(ub1, uv1) * Closed(3)
            assert(varWidth.toInt == IntervalType(lv, uv, bp).width.asInstanceOf[IntWidth].width)
          case ShiftRightPattern(varWidth)     =>
            val bp = IntWidth(bp1.toInt)
            val lv = getBound(lb1, lv1) * Closed(1/3)
            val uv = getBound(ub1, uv1) * Closed(1/3)
            assert(varWidth.toInt == IntervalType(lv, uv, bp).width.asInstanceOf[IntWidth].width)
          case ComparisonPattern(varWidth) => assert(varWidth.toInt == 1)
          case ArithAssignPattern(varName, operation, args) =>
            val arg1 = if(IntervalType(getBound(lb1, lv1), getBound(ub1, uv1), IntWidth(bp1)).width == IntWidth(0)) """SInt<1>("h0")""" else "in1"
            val arg2 = if(IntervalType(getBound(lb2, lv2), getBound(ub2, uv2), IntWidth(bp2)).width == IntWidth(0)) """SInt<1>("h0")""" else "in2"
            varName match {
              case "sum" =>
                assert(operation === "add", s"""var sum should be result of an add in ${output.mkString("\n")}""")
                if (bp1 > bp2) {
                  if (arg1 != arg2) assert(!args.contains(s"shl($arg1"), s"$config first arg should be just $arg1 in $line")
                  assert(args.contains(s"shl($arg2, ${bp1 - bp2})"),
                    s"$config second arg incorrect in $line")
                } else if (bp1 < bp2) {
                  assert(args.contains(s"shl($arg1, ${(bp1 - bp2).abs})"),
                    s"$config second arg incorrect in $line")
                  assert(!args.contains("shl($arg2"), s"$config second arg should be just $arg2 in $line")
                } else {
                  assert(!args.contains(s"shl($arg1"), s"$config first arg should be just $arg1 in $line")
                  assert(!args.contains(s"shl($arg2"), s"$config second arg should be just $arg2 in $line")
                }
              case "product" =>
                assert(operation === "mul", s"var sum should be result of an add in $line")
                assert(!args.contains(s"shl($arg1"), s"$config first arg should be just $arg1 in $line")
                assert(!args.contains(s"shl($arg2"), s"$config second arg should be just $arg2 in $line")
              case "difference" =>
                assert(operation === "sub", s"var difference should be result of an sub in $line")
                if (bp1 > bp2) {
                  if (arg1 != arg2) assert(!args.contains(s"shl($arg1"), s"$config first arg should be just $arg1 in $line")
                  assert(args.contains(s"shl($arg2, ${bp1 - bp2})"),
                    s"$config second arg incorrect in $line")
                } else if (bp1 < bp2) {
                  assert(args.contains(s"shl($arg1, ${(bp1 - bp2).abs})"),
                    s"$config second arg incorrect in $line")
                  if (arg1 != arg2) assert(!args.contains(s"shl($arg2"), s"$config second arg should be just $arg2 in $line")
                } else {
                  assert(!args.contains(s"shl($arg1"), s"$config first arg should be just $arg1 in $line")
                  assert(!args.contains(s"shl($arg2"), s"$config second arg should be just $arg2 in $line")
                }
              case _ =>
            }
          case _ =>
        }
      }
    }
  }
}


// vim: set ts=4 sw=4 et:
