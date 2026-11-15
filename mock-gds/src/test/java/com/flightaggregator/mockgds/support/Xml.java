package com.flightaggregator.mockgds.support;

import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/**
 * Reads the XML the mock writes, in tests. A parser of real provider responses must also refuse
 * DTDs and external entities, which this default reader does not.
 */
public final class Xml {

  private Xml() {}

  /** The root element of the text. */
  public static Element parse(String text) throws Exception {
    return DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(new InputSource(new StringReader(text)))
        .getDocumentElement();
  }
}
