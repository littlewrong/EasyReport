package cn.easyreport.definition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.annotate.JsonSerialize.Inclusion;
import org.dom4j.DocumentHelper;
import org.junit.Test;

import cn.easyreport.definition.value.SimpleValue;
import cn.easyreport.definition.value.Value;
import cn.easyreport.definition.value.ValueType;
import cn.easyreport.model.Cell;
import cn.easyreport.parser.impl.CellParser;

public class HtmlCellMetadataTest {

	@Test
	public void oldAndExplicitlyDisabledCellsRemainPlainText() throws Exception {
		for(String attributes:new String[]{"", "html='false'", "html='invalid'"}){
			CellDefinition definition=parse(attributes);
			assertFalse(definition.isHtml());
			assertFalse(definition.newCell().isHtmlRender());
			assertEquals("<b>value</b>",definition.getValue().getValue());
		}
	}

	@Test
	public void enabledFlagSurvivesDefinitionAndRuntimeCopies() throws Exception {
		CellDefinition definition=parse("html='true'");
		assertTrue(definition.isHtml());
		Cell cell=definition.newCell();
		assertTrue(cell.isHtml());
		assertTrue(cell.isHtmlRender());
		Cell copy=cell.newCell();
		assertTrue(copy.isHtml());
		assertTrue(copy.isHtmlRender());
		assertEquals("<b>value</b>",copy.getValue().getValue());
		cell.setHtml(false);
		assertFalse(cell.newCell().isHtml());
	}

	@Test
	public void onlyEnabledTextAndExpressionTypesRenderHtml() {
		Cell cell=new Cell();
		cell.setHtml(true);
		assertFalse(cell.isHtmlRender());
		for(final ValueType type:ValueType.values()){
			cell.setValue(new Value(){
				@Override
				public String getValue(){
					return "<b>value</b>";
				}
				@Override
				public ValueType getType(){
					return type;
				}
			});
			assertEquals(type.toString(),type==ValueType.simple || type==ValueType.expression,cell.isHtmlRender());
		}
		cell.setValue(new SimpleValue("<b>value</b>"));
		cell.setHtml(false);
		assertFalse(cell.isHtmlRender());
	}

	@Test
	public void designerJsonIncludesFlagEvenWhenFalse() throws Exception {
		ObjectMapper mapper=new ObjectMapper();
		mapper.setSerializationInclusion(Inclusion.NON_NULL);
		CellDefinition definition=parse("");
		JsonNode json=mapper.readTree(mapper.writeValueAsString(definition));
		assertTrue(json.has("html"));
		assertFalse(json.get("html").getBooleanValue());
		definition.setHtml(true);
		json=mapper.readTree(mapper.writeValueAsString(definition));
		assertTrue(json.get("html").getBooleanValue());
		JsonNode cellJson=mapper.valueToTree(definition.newCell());
		assertFalse(cellJson.has("htmlRender"));
	}

	private CellDefinition parse(String attributes) throws Exception {
		String xml="<cell name='A1' row='1' col='1' "+attributes+"><simple-value><![CDATA[<b>value</b>]]></simple-value></cell>";
		return new CellParser().parse(DocumentHelper.parseText(xml).getRootElement());
	}
}
