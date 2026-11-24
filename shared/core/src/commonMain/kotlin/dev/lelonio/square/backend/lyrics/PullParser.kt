package dev.lelonio.square.backend.lyrics

import org.xmlpull.v1.XmlPullParser

/** Android ships its own XmlPull implementation; the desktop uses kxml2. */
expect fun newPullParser(): XmlPullParser
