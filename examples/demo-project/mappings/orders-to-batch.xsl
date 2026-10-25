<?xml version="1.0" encoding="UTF-8"?>
<!-- Demo (XSLT 3.0): source orders -> SalesOrderBatch. Try it with inputs/orders.xml;
     the result is the input of scripts/validate-order-batch.groovy. -->
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:src="urn:demo:integration:orders:source:v1"
                xmlns="urn:demo:integration:orders:target:v1"
                exclude-result-prefixes="xs src">

    <xsl:output method="xml" encoding="UTF-8" indent="yes"/>
    <xsl:strip-space elements="*"/>

    <xsl:template match="/src:Orders">
        <SalesOrderBatch>
            <Header>
                <SourceSystem>
                    <xsl:value-of select="src:SourceSystem"/>
                </SourceSystem>
                <OrderCount>
                    <xsl:value-of select="count(src:Order)"/>
                </OrderCount>
            </Header>
            <SalesOrders>
                <xsl:for-each select="src:Order">
                    <!-- The simple map operator (!) is an XPath 3.0 feature. -->
                    <xsl:variable name="orderTotal" as="xs:decimal"
                                  select="sum(src:Items/src:Item !
                                          (xs:decimal(src:Quantity) * xs:decimal(src:UnitPrice)))"/>
                    <SalesOrder>
                        <OrderID>
                            <xsl:value-of select="@orderNumber"/>
                        </OrderID>
                        <OrderDate>
                            <xsl:value-of select="@orderDate"/>
                        </OrderDate>
                        <Customer>
                            <ID>
                                <xsl:value-of select="src:Customer/@customerId"/>
                            </ID>
                            <Name>
                                <xsl:value-of select="src:Customer/src:Name"/>
                            </Name>
                            <Country>
                                <xsl:value-of select="src:Customer/src:Country"/>
                            </Country>
                        </Customer>
                        <Items>
                            <xsl:for-each select="src:Items/src:Item">
                                <Item>
                                    <Position>
                                        <xsl:value-of select="@position"/>
                                    </Position>
                                    <ProductID>
                                        <xsl:value-of select="src:Material"/>
                                    </ProductID>
                                    <Description>
                                        <xsl:value-of select="src:Description"/>
                                    </Description>
                                    <Quantity>
                                        <xsl:value-of select="src:Quantity"/>
                                    </Quantity>
                                    <UnitPrice>
                                        <xsl:value-of select="format-number(xs:decimal(src:UnitPrice), '0.00')"/>
                                    </UnitPrice>
                                    <LineAmount>
                                        <xsl:value-of select="format-number(
                                            xs:decimal(src:Quantity) * xs:decimal(src:UnitPrice), '0.00')"/>
                                    </LineAmount>
                                </Item>
                            </xsl:for-each>
                        </Items>
                        <TotalAmount currency="{normalize-space(src:Currency)}">
                            <xsl:value-of select="format-number($orderTotal, '0.00')"/>
                        </TotalAmount>
                    </SalesOrder>
                </xsl:for-each>
            </SalesOrders>
        </SalesOrderBatch>
    </xsl:template>
</xsl:stylesheet>
