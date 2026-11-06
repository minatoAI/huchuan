
import org.libtorrent4j.alerts.*;
public class Probe2 {
  public static void main(String[] a) throws Exception {
    java.lang.reflect.Field tf = AlertType.class.getDeclaredField("TABLE");
    tf.setAccessible(true);
    AlertType[] t = (AlertType[]) tf.get(null);
    for (int i = 0; i < t.length; i++) System.out.println(i + " " + (t[i]==null?"<NULL>":t[i].name()));
  }
}