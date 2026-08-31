import com.sola.universalmarket.util.NumberFormatter;
import java.math.BigDecimal;

public class NumberFormatterTest {
    static int pass=0, fail=0;
    static void eq(String label, String got, String want){
        if(got.equals(want)){pass++; System.out.printf("  ok   %-22s %s%n",label,got);}
        else {fail++; System.out.printf("  FAIL %-22s got=%s want=%s%n",label,got,want);}
    }
    static void m(long v,String want){ eq(String.valueOf(v), NumberFormatter.money(v), want); }
    public static void main(String[] a){
        System.out.println("--- spec section 5: below 100,000 -> full number ---");
        m(0,"$0"); m(54,"$54"); m(9500,"$9,500"); m(99999,"$99,999");
        System.out.println("--- spec section 5: 100,000+ -> abbreviated ---");
        m(100000,"$100K"); m(145500,"$145.5K"); m(999999,"$999.99K");
        m(1000000,"$1M"); m(18420000,"$18.42M"); m(100000000,"$100M"); m(500000000,"$500M");
        m(1000000000L,"$1B"); m(500000000000L,"$500B");
        m(1000000000000L,"$1T"); m(500000000000000L,"$500T");
        m(1000000000000000L,"$1Q");
        System.out.println("--- spec section 5: no cents, no trailing zeros ---");
        m(18500000,"$18.5M");      // $18.50M -> $18.5M
        m(100000000,"$100M");      // $100.00M -> $100M
        System.out.println("--- wealth tier thresholds from spec ---");
        m(500000000L,"$500M"); m(500000000000L,"$500B"); m(500000000000000L,"$500T");
        System.out.println("--- live balance example (section 12) ---");
        m(18420000,"$18.42M"); m(18419450,"$18.41M");
        System.out.println("--- price anchors (section 20) ---");
        m(550,"$550"); m(70000,"$70,000"); NumberFormatter.setAbbreviateAt(10000); m(70000,"$70K"); NumberFormatter.setAbbreviateAt(100000); m(9000,"$9,000"); m(250000,"$250K");
        m(5000000,"$5M"); m(250000000,"$250M");
        System.out.println("--- payment fee example (section 34): 7.45% of 10M ---");
        BigDecimal send=new BigDecimal("10000000");
        BigDecimal fee=send.multiply(new BigDecimal("0.0745")).setScale(0,java.math.RoundingMode.HALF_UP);
        eq("fee", NumberFormatter.money(fee), "$745K");
        eq("total", NumberFormatter.money(send.add(fee)), "$10.74M");
        eq("total exact", NumberFormatter.exactMoney(send.add(fee)), "$10,745,000");
        System.out.println("--- negatives / edge ---");
        m(-550,"-$550"); m(-18420000,"-$18.42M");
        eq("null", NumberFormatter.money((BigDecimal)null), "$0");
        eq("exact", NumberFormatter.exact(new BigDecimal("18420000")), "18,420,000");
        System.out.println("--- parse ---");
        eq("parse 10m", String.valueOf(NumberFormatter.parse("10m")), "10000000");
        eq("parse $1.5b", String.valueOf(NumberFormatter.parse("$1.5b")), "1500000000");
        eq("parse 9,500", String.valueOf(NumberFormatter.parse("9,500")), "9500");
        eq("parse 500T", String.valueOf(NumberFormatter.parse("500T")), "500000000000000");
        eq("parse neg", String.valueOf(NumberFormatter.parse("-5")), "null");
        eq("parse junk", String.valueOf(NumberFormatter.parse("abc")), "null");
        eq("parse NaN", String.valueOf(NumberFormatter.parse("NaN")), "null");
        eq("parse Inf", String.valueOf(NumberFormatter.parse("Infinity")), "null");
        eq("parse empty", String.valueOf(NumberFormatter.parse("")), "null");
        eq("parse huge", String.valueOf(NumberFormatter.parse("99999999999999999999")), "null");
        System.out.println("--- duration / percent ---");
        eq("dur", NumberFormatter.duration(4920000L), "1h 22m");
        eq("pct", NumberFormatter.percent(0.2), "20%");
        eq("pct2", NumberFormatter.percent(0.425), "42.5%");
        System.out.printf("%n=== PASS %d / FAIL %d ===%n",pass,fail);
        if(fail>0) System.exit(1);
    }
}
