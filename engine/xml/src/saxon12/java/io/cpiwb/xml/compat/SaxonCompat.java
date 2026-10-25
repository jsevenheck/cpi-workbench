package io.cpiwb.xml.compat;

import io.cpiwb.xml.CpiCallback;
import io.cpiwb.xml.JavaExtensions;
import io.cpiwb.xml.XslMessage;
import net.sf.saxon.Configuration;
import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.jaxp.TransformerImpl;
import net.sf.saxon.lib.ExtensionFunctionCall;
import net.sf.saxon.lib.ExtensionFunctionDefinition;
import net.sf.saxon.om.Item;
import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.om.Sequence;
import net.sf.saxon.om.SequenceIterator;
import net.sf.saxon.om.StructuredQName;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.value.AtomicValue;
import net.sf.saxon.value.BigDecimalValue;
import net.sf.saxon.value.BooleanValue;
import net.sf.saxon.value.DecimalValue;
import net.sf.saxon.value.DoubleValue;
import net.sf.saxon.value.EmptySequence;
import net.sf.saxon.value.FloatValue;
import net.sf.saxon.value.Int64Value;
import net.sf.saxon.value.IntegerValue;
import net.sf.saxon.value.StringValue;
import net.sf.saxon.value.ObjectValue;
import net.sf.saxon.value.SequenceType;

import javax.xml.transform.Transformer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Saxon 12 specific glue. The Saxon 9.9 variant lives in src/saxon99. */
public final class SaxonCompat {

    public static final String PROFILE = "saxon-he-12";
    public static final String PROFILE_LABEL = "Modern HE (Saxon-HE 12)";

    private SaxonCompat() {
    }

    public static void captureMessages(Transformer transformer, Consumer<XslMessage> sink) {
        ((TransformerImpl) transformer).getUnderlyingXsltTransformer().setMessageHandler(m -> sink.accept(
                new XslMessage(m.getStringValue(), m.isTerminate(),
                        m.getLocation() == null ? -1 : m.getLocation().getLineNumber(),
                        m.getLocation() == null ? null : m.getLocation().getSystemId())));
    }

    public static void registerCpiFunctions(Configuration config, CpiCallback callback) {
        config.registerExtensionFunction(new CpiFunction("setHeader", callback));
        config.registerExtensionFunction(new CpiFunction("setProperty", callback));
    }

    /** Registers a reflexive Java extension function (see {@link JavaExtensions}). */
    public static void registerJavaFunction(Configuration config, String namespace, String localName,
                                            JavaExtensions.Invoker invoker) {
        config.registerExtensionFunction(new ExtensionFunctionDefinition() {
            @Override
            public StructuredQName getFunctionQName() {
                return new StructuredQName("", namespace, localName);
            }

            @Override
            public int getMinimumNumberOfArguments() {
                return 0;
            }

            @Override
            public int getMaximumNumberOfArguments() {
                return JavaExtensions.MAX_ARGS;
            }

            @Override
            public SequenceType[] getArgumentTypes() {
                SequenceType[] t = new SequenceType[JavaExtensions.MAX_ARGS];
                java.util.Arrays.fill(t, SequenceType.ANY_SEQUENCE);
                return t;
            }

            @Override
            public SequenceType getResultType(SequenceType[] suppliedArgumentTypes) {
                return SequenceType.ANY_SEQUENCE;
            }

            @Override
            public boolean hasSideEffects() {
                return true;
            }

            @Override
            public ExtensionFunctionCall makeCallExpression() {
                return new ExtensionFunctionCall() {
                    @Override
                    public Sequence call(XPathContext context, Sequence[] arguments) throws XPathException {
                        Object[] args = new Object[arguments.length];
                        for (int i = 0; i < arguments.length; i++) {
                            args[i] = toJava(arguments[i]);
                        }
                        try {
                            return fromJava(invoker.invoke(args));
                        } catch (Exception e) {
                            throw new XPathException("Java extension " + localName + " (" + namespace + "): "
                                    + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
                        }
                    }
                };
            }
        });
    }

    private static Object toJava(Sequence seq) throws XPathException {
        List<Object> items = new ArrayList<>();
        SequenceIterator it = seq.iterate();
        Item item;
        while ((item = it.next()) != null) {
            items.add(itemToJava(item));
        }
        return items.isEmpty() ? null : items.size() == 1 ? items.get(0) : items;
    }

    private static Object itemToJava(Item item) throws XPathException {
        if (item instanceof ObjectValue) {
            return ((ObjectValue<?>) item).getObject();
        }
        if (item instanceof NodeInfo || !(item instanceof AtomicValue)) {
            return item.getStringValue().toString();
        }
        if (item instanceof BooleanValue) {
            return ((BooleanValue) item).getBooleanValue();
        }
        if (item instanceof Int64Value) {
            return ((Int64Value) item).longValue();
        }
        if (item instanceof IntegerValue) {
            return ((IntegerValue) item).asBigInteger();
        }
        if (item instanceof DecimalValue) {
            return ((DecimalValue) item).getDecimalValue();
        }
        if (item instanceof DoubleValue) {
            return ((DoubleValue) item).getDoubleValue();
        }
        if (item instanceof FloatValue) {
            return ((FloatValue) item).getFloatValue();
        }
        return item.getStringValue().toString();
    }

    private static Sequence fromJava(Object v) {
        if (v == null) {
            return EmptySequence.getInstance();
        }
        if (v instanceof String || v instanceof Character) {
            return StringValue.makeStringValue(v.toString());
        }
        if (v instanceof Boolean) {
            return BooleanValue.get((Boolean) v);
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return new Int64Value(((Number) v).longValue());
        }
        if (v instanceof BigInteger) {
            return IntegerValue.makeIntegerValue((BigInteger) v);
        }
        if (v instanceof BigDecimal) {
            return new BigDecimalValue((BigDecimal) v);
        }
        if (v instanceof Double) {
            return new DoubleValue((Double) v);
        }
        if (v instanceof Float) {
            return new FloatValue((Float) v);
        }
        return new ObjectValue<>(v);
    }

    private static final class CpiFunction extends ExtensionFunctionDefinition {
        private final String name;
        private final CpiCallback callback;

        CpiFunction(String name, CpiCallback callback) {
            this.name = name;
            this.callback = callback;
        }

        @Override
        public StructuredQName getFunctionQName() {
            return new StructuredQName("hci", CpiCallback.NAMESPACE, name);
        }

        @Override
        public SequenceType[] getArgumentTypes() {
            return new SequenceType[] {SequenceType.ANY_SEQUENCE, SequenceType.ANY_SEQUENCE, SequenceType.ANY_SEQUENCE};
        }

        @Override
        public SequenceType getResultType(SequenceType[] suppliedArgumentTypes) {
            return SequenceType.EMPTY_SEQUENCE;
        }

        @Override
        public boolean hasSideEffects() {
            return true;
        }

        @Override
        public ExtensionFunctionCall makeCallExpression() {
            return new ExtensionFunctionCall() {
                @Override
                public Sequence call(XPathContext context, Sequence[] arguments) throws XPathException {
                    Item first = arguments[0].head();
                    Object exchange = first instanceof ObjectValue ? ((ObjectValue<?>) first).getObject() : first;
                    try {
                        callback.call(name, exchange, text(arguments[1]), text(arguments[2]));
                    } catch (RuntimeException e) {
                        throw new XPathException(e.getMessage());
                    }
                    return EmptySequence.getInstance();
                }
            };
        }
    }

    private static String text(Sequence seq) throws XPathException {
        StringBuilder sb = new StringBuilder();
        SequenceIterator it = seq.iterate();
        Item item;
        while ((item = it.next()) != null) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(item.getStringValue());
        }
        return sb.toString();
    }
}
