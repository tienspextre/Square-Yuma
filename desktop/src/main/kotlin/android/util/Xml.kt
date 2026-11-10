package android.util

import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/** The one piece of android.util the shared TTML reader uses: Android's own parser is kXML too. */
object Xml {
    fun newPullParser(): XmlPullParser = KXmlParser()
}
