<?xml version="1.0" encoding="UTF-8"?>
<!-- CPI Workbench – XSLT engine fingerprint. Use as XSLT Mapping (step version 1.2+) on any input. -->
<xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema" exclude-result-prefixes="xs">
  <xsl:output method="xml" indent="yes"/>
  <xsl:template match="/">
    <XsltFingerprint>
      <xsl:for-each select="('xsl:version', 'xsl:vendor', 'xsl:vendor-url', 'xsl:product-name', 'xsl:product-version',
                             'xsl:is-schema-aware', 'xsl:supports-serialization', 'xsl:supports-backwards-compatibility',
                             'xsl:supports-namespace-axis', 'xsl:supports-streaming', 'xsl:supports-dynamic-evaluation',
                             'xsl:supports-higher-order-functions', 'xsl:xpath-and-xquery-version')">
        <Property name="{.}"><xsl:value-of select="system-property(.)"/></Property>
      </xsl:for-each>
      <ImplicitTimezone><xsl:value-of select="implicit-timezone()"/></ImplicitTimezone>
      <DefaultCollation><xsl:value-of select="default-collation()"/></DefaultCollation>
      <!-- Localisation: Saxon-HE only knows English month names; EE/PE (or ICU) know German. -->
      <FormatDateGerman><xsl:value-of select="format-date(xs:date('2026-03-05'), '[D1]. [MNn] [Y0001]', 'de', (), ())"/></FormatDateGerman>
      <FormatNumber><xsl:value-of select="format-number(-1234567.891, '#,##0.00')"/></FormatNumber>
      <FunctionAvailable name="saxon:evaluate"><xsl:value-of select="function-available('saxon:evaluate', 1)" xmlns:saxon="http://saxon.sf.net/"/></FunctionAvailable>
    </XsltFingerprint>
  </xsl:template>
</xsl:stylesheet>
