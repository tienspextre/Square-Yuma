package dev.lelonio.square.backend.lyrics

import org.xmlpull.v1.XmlPullParser

actual fun newPullParser(): XmlPullParser = android.util.Xml.newPullParser()
