p = "macro-engine/app/src/main/AndroidManifest.xml"
s = open(p).read()
block = '''        <service
            android:name=".FloatingOverlayService"
            android:exported="false" />
'''
if "FloatingOverlayService" not in s:
    s = s.replace("</application>", block + "    </application>")
    open(p, "w").write(s)
    print("registered FloatingOverlayService")
else:
    print("already registered")
