package io.cpiwb.xml.compat;

import io.cpiwb.xml.CpiCallback;
import io.cpiwb.xml.XslMessage;
import net.sf.saxon.Configuration;
import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.jaxp.TransformerImpl;
import net.sf.saxon.lib.ExtensionFunctionCall;
import net.sf.saxon.lib.ExtensionFunctionDefinition;
import net.sf.saxon.om.Item;
import net.sf.saxon.om.Sequence;
import net.sf.saxon.om.SequenceIterator;
import net.sf.saxon.om.StructuredQName;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.value.EmptySequence;
import net.sf.saxon.value.ObjectValue;
import net.sf.saxon.value.SequenceType;

import javax.xml.transform.Transformer;
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
