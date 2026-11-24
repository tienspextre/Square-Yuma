package dev.lelonio.square.backend.lyrics

import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

actual fun newPullParser(): XmlPullParser = KXmlParser()
