
import org.libtorrent4j.alerts.*;
public class Probe {
  public static void main(String[] a) throws Exception {
    System.out.println("NUM_ALERT_TYPES=" + Alerts.NUM_ALERT_TYPES);
    System.out.println("AlertType constants=" + AlertType.class.getEnumConstants().length);
    java.lang.reflect.Field tf = AlertType.class.getDeclaredField("TABLE");
    tf.setAccessible(true);
    AlertType[] t = (AlertType[]) tf.get(null);
    System.out.println("TABLE length=" + t.length);
    int nulls = 0, unknowns = 0;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < t.length; i++) {
      if (t[i] == null) { nulls++; sb.append("  idx ").append(i).append(" -> null\n"); }
      else if (t[i] == AlertType.UNKNOWN) unknowns++;
    }
    System.out.println("nulls=" + nulls + " unknowns=" + unknowns);
    System.out.print(sb);
  }
}