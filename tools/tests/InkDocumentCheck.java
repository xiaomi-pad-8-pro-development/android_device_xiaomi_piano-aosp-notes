package com.alxp.pianonotes;
import org.json.*;
import java.util.*;
public class InkDocumentCheck {
 static int checks;
 static void check(boolean yes) { checks++; if (!yes) throw new AssertionError(checks); }
 static void reject(String text) throws Exception {
  try { InkDocument.read(text); throw new AssertionError("Accepted: " + text); }
  catch (JSONException expected) { checks++; }
 }
 public static void main(String[] args) throws Exception {
  String legacy = "[{\"pts\":[{\"x\":10,\"y\":20,\"p\":0.5}],\"pen\":\"PENCIL\"}]";
  var old = InkDocument.read(legacy);
  check(old.size()==1);
  check(old.get(0).getJSONObject(0).getJSONArray("pts").getJSONObject(0).getDouble("p")==0.5);
  check(new JSONArray(InkDocument.write(old)).similar(new JSONArray(legacy)));
  check(InkDocument.read("[]").size()==1);
  var pages = new ArrayList<JSONArray>(old); pages.add(new JSONArray());
  var reopened = InkDocument.read(InkDocument.write(pages));
  check(reopened.size()==2);
  check(reopened.get(0).similar(new JSONArray(legacy)));
  check(reopened.get(1).length()==0);
  reject("null"); reject("[] trailing"); reject("{}");
  reject("{\"format\":\"alxp-ink\",\"version\":2,\"pages\":[[]]}");
  reject("{\"format\":\"alxp-ink\",\"version\":1,\"pages\":[]}");
  reject("{\"format\":\"alxp-ink\",\"version\":1,\"pages\":[{}]}");
  reject("{\"format\":\"alxp-ink\",\"version\":1.5,\"pages\":[[]]}");
  reject("{\"format\":\"alxp-ink\",\"version\":\"1\",\"pages\":[[]]}");
  try { InkDocument.write(Arrays.asList((JSONArray)null)); throw new AssertionError(); }
  catch (JSONException expected) { checks++; }
  JSONArray tooMany = new JSONArray(); for(int i=0;i<257;i++) tooMany.put(new JSONArray());
  reject(new JSONObject().put("format","alxp-ink").put("version",1).put("pages",tooMany).toString());
  System.out.println(checks + " compatibility checks passed");
 }
}
