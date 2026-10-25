<?xml version="1.0" encoding="UTF-8"?>
<!-- CPI Workbench – XSLT Mapping behaviour probe. Run after prepare-xslt-probe.groovy.
     Answers docs/plan.md Appendix A questions Q-X2 (precedence), Q-X3 (types), Q-X4 (missing params),
     Q-X5 (setHeader return value). If the step fails, the error text answers Q-X4. -->
<xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:hci="http://sap.com/it/"
    xmlns:p="urn:cpiwb:probe" exclude-result-prefixes="xs hci p">
  <xsl:param name="exchange"/>
  <xsl:param name="ProbeShared"/>
  <xsl:param name="ProbeHeaderOnly"/>
  <xsl:param name="ProbePropertyOnly"/>
  <xsl:param name="ProbeInteger"/>
  <xsl:param name="ProbeBoolean"/>
  <xsl:param name="ProbeDate"/>
  <xsl:param name="ProbeBytes"/>
  <xsl:param name="ProbeMissing" select="'DEFAULT-USED'"/>
  <xsl:output method="xml" indent="yes"/>

  <xsl:function name="p:describe" as="xs:string">
    <xsl:param name="v"/>
    <xsl:sequence select="concat('count=', count($v), '; value=', string-join(for $i in $v return string($i), ' '),
      '; integer=', $v instance of xs:integer, '; int=', $v instance of xs:int, '; string=', $v instance of xs:string,
      '; boolean=', $v instance of xs:boolean, '; dateTime=', $v instance of xs:dateTime,
      '; untyped=', $v instance of xs:untypedAtomic, '; node=', $v instance of node())"/>
  </xsl:function>

  <xsl:template match="/">
    <xsl:variable name="ret" select="hci:setHeader($exchange, 'ProbeSetByXslt', 'x')"/>
    <XsltBehaviourProbe>
      <SharedName><xsl:value-of select="$ProbeShared"/></SharedName>
      <HeaderOnly><xsl:value-of select="$ProbeHeaderOnly"/></HeaderOnly>
      <PropertyOnly><xsl:value-of select="$ProbePropertyOnly"/></PropertyOnly>
      <Integer><xsl:value-of select="p:describe($ProbeInteger)"/></Integer>
      <Boolean><xsl:value-of select="p:describe($ProbeBoolean)"/></Boolean>
      <Date><xsl:value-of select="p:describe($ProbeDate)"/></Date>
      <Bytes><xsl:value-of select="p:describe($ProbeBytes)"/></Bytes>
      <Missing><xsl:value-of select="$ProbeMissing"/></Missing>
      <SetHeaderReturn><xsl:value-of select="p:describe($ret)"/></SetHeaderReturn>
      <ExchangeParam><xsl:value-of select="p:describe($exchange)"/></ExchangeParam>
    </XsltBehaviourProbe>
  </xsl:template>
</xsl:stylesheet>
