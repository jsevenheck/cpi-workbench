// Examples that load into the editors without a project. They are files of examples/demo-project, bundled with the
// UI (the installers do not ship that folder); examples that need the project's other files (xsl:include, the
// platform/ data) are left out.
import ordersToBatch from '../../../examples/demo-project/mappings/orders-to-batch.xsl?raw';
import orders from '../../../examples/demo-project/inputs/orders.xml?raw';
import validateBatch from '../../../examples/demo-project/scripts/validate-order-batch.groovy?raw';
import orderBatchXml from '../../../examples/demo-project/inputs/order-batch.xml?raw';
import validateBatchJson from '../../../examples/demo-project/scripts/validate-order-batch-json.groovy?raw';
import orderBatchJson from '../../../examples/demo-project/inputs/order-batch.json?raw';

export interface Example {
  id: string;
  title: string;
  tool: 'xslt' | 'groovy';
  /** The demo project's path of the mapping or script, for the status line. */
  path: string;
  source: string;
  body: string;
  /** The Groovy runtime it is written for. */
  profile?: 'groovy-1.x' | 'groovy-2.x';
}

export const EXAMPLES: Example[] = [
  { id: 'xslt-batch', title: 'XSLT 3.0: orders to a sales order batch', tool: 'xslt',
    path: 'mappings/orders-to-batch.xsl', source: ordersToBatch, body: orders },
  { id: 'groovy-xml', title: 'Groovy 2.x: validate the batch (XML), set headers', tool: 'groovy', profile: 'groovy-2.x',
    path: 'scripts/validate-order-batch.groovy', source: validateBatch, body: orderBatchXml },
  { id: 'groovy-json', title: 'Groovy 2.x: validate the batch (JSON)', tool: 'groovy', profile: 'groovy-2.x',
    path: 'scripts/validate-order-batch-json.groovy', source: validateBatchJson, body: orderBatchJson },
];
