<?xml version="1.0" encoding="UTF-8"?>
<!--
  Web shop order -> S/4HANA sales order (demo).
  Like in Cloud Integration:
    * header SenderSystem and property CustomerGroup arrive as xsl:param,
    * $exchange + hci:setHeader / hci:setProperty change the message.
-->
<xsl:stylesheet version="2.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:hci="http://sap.com/it/"
    xmlns:f="urn:demo:functions"
    exclude-result-prefixes="xs hci f">

  <xsl:include href="common.xsl"/>

  <xsl:param name="exchange"/>
  <xsl:param name="SenderSystem" select="'UNKNOWN'"/>
  <xsl:param name="CustomerGroup" select="'01'"/>

  <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

  <xsl:template match="/Order">
    <xsl:value-of select="hci:setHeader($exchange, 'SAP_ApplicationID', @number)"/>
    <xsl:value-of select="hci:setProperty($exchange, 'ItemCount', string(count(Items/Item)))"/>
    <xsl:message>Order <xsl:value-of select="@number"/> from <xsl:value-of select="$SenderSystem"/></xsl:message>

    <SalesOrder>
      <Header>
        <ExternalReference><xsl:value-of select="@number"/></ExternalReference>
        <SoldTo><xsl:value-of select="Customer/@id"/></SoldTo>
        <CustomerGroup><xsl:value-of select="$CustomerGroup"/></CustomerGroup>
        <ShippingCondition><xsl:value-of select="f:shipping-condition(Shipping/@code)"/></ShippingCondition>
        <Source><xsl:value-of select="$SenderSystem"/></Source>
        <OrderDate><xsl:value-of select="format-date(xs:date(@date), '[Y0001][M01][D01]')"/></OrderDate>
      </Header>
      <xsl:for-each select="Items/Item">
        <Item>
          <Position><xsl:value-of select="format-number(position() * 10, '000000')"/></Position>
          <Material><xsl:value-of select="upper-case(@sku)"/></Material>
          <Quantity><xsl:value-of select="@qty"/></Quantity>
          <NetAmount><xsl:value-of select="format-number(@qty * @price, '0.00')"/></NetAmount>
        </Item>
      </xsl:for-each>
      <TotalNetAmount><xsl:value-of select="format-number(sum(Items/Item/(@qty * @price)), '0.00')"/></TotalNetAmount>
    </SalesOrder>
  </xsl:template>
</xsl:stylesheet>
