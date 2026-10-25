<?xml version="1.0" encoding="UTF-8"?>
<!-- Shared helpers, included by order-to-salesorder.xsl (like a resource in the same iFlow). -->
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:f="urn:demo:functions" exclude-result-prefixes="xs f">

  <!-- Maps the shop's shipping codes to S/4HANA shipping conditions. -->
  <xsl:function name="f:shipping-condition" as="xs:string">
    <xsl:param name="code" as="xs:string?"/>
    <xsl:sequence select="if ($code = 'EXPRESS') then '02' else if ($code = 'PICKUP') then '10' else '01'"/>
  </xsl:function>
</xsl:stylesheet>
