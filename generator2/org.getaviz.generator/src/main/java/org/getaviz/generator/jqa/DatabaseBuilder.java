package org.getaviz.generator.jqa;

import org.getaviz.generator.SettingsConfiguration;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import java.io.IOException;
import org.getaviz.generator.database.DatabaseConnector;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Value;
import org.neo4j.driver.v1.types.Node;

public class DatabaseBuilder {
	Log log = LogFactory.getLog(this.getClass());
	SettingsConfiguration config = SettingsConfiguration.getInstance();
	DatabaseConnector connector = DatabaseConnector.getInstance();
	Runtime runtime = Runtime.getRuntime();

	public DatabaseBuilder() {
		scan();
		enhance();
	}

	public void scan() {
		log.info("jQA scan started.");
		log.info("Scanning from URI(s) " + config.getInputFiles());
		try {
			Process pScan = runtime.exec("/opt/jqassistant/bin/jqassistant.sh scan -reset -u " + config.getInputFiles() + " -storeUri " +
					DatabaseConnector.getDatabaseURL());
			pScan.waitFor();
		} catch (InterruptedException e) {
			log.error(e);
			e.printStackTrace();
		} catch (IOException e) {
			log.error(e);
			e.printStackTrace();
		}
		log.info("jQA scan ended.");
	}

	public void enhance() {
		log.info("jQA enhancement started.");
		connector.executeWrite(labelGetter(), labelSetter(), labelPrimitives(), labelInnerTypes());
		connector.executeWrite(labelAnonymousInnerTypes());
		addHashes();
		log.info("jQA enhancement finished");
	}

	private void addHashes() {
		connector.executeRead(collectAllPackages()).forEachRemaining((result) -> { enhanceNode(result); });
		connector.executeRead(collectAllTypes()).forEachRemaining((result) -> { enhanceNode(result); });
		connector.executeRead(collectAllFields()).forEachRemaining((result) -> { enhanceNode(result); });
		connector.executeRead(collectAllMethods()).forEachRemaining((result) -> { enhanceNode(result); });
	}

	private String createHash(String fqn) {
		return "ID_" + DigestUtils.sha1Hex(fqn + config.getRepositoryName() + config.getRepositoryOwner());
	}

	private String labelPrimitives() {
		return "MATCH (n:Type) WHERE n.name =~ \"[a-z]+\" SET n:Primitive";
	}

	private String labelGetter() {
		return "MATCH (o:Type)-[:DECLARES]->(method:Method)-[getter:READS]->(attribute:Field)<-[:DECLARES]-(q:Type) " + 
				"WHERE method.name =~ \"get[A-Z]+[A-Za-z]*\" " + 
				"AND toLower(method.name) contains(attribute.name) AND ID(o) = ID(q) " + 
				"SET method:Getter";
	}

	private String labelSetter() {
		return "MATCH (o:Type)-[:DECLARES]->(method:Method)-[setter:WRITES]->(attribute:Field)<-[:DECLARES]-(q:Type) " + 
				"WHERE method.name =~ \"set[A-Z]+[A-Za-z]*\" " +
				"AND toLower(method.name) contains(attribute.name) AND ID(o) = ID(q) " + 
				"SET method:Setter";
	}

	private String labelInnerTypes() {
		return "MATCH (:Type)-[:DECLARES]->(innerType:Type) SET innerType:Inner";
	}

	private String labelAnonymousInnerTypes() {
		return "MATCH (innerType:Inner:Type) WHERE innerType.name =~ \".*\\\\$[0-9]*\" SET innerType:Anonymous";
	}

	private String collectAllPackages() {
		return "MATCH (n:Package) RETURN n";
	}

	private String collectAllTypes() {
		return "MATCH (n:Type) " +
				"WHERE (n:Interface OR n:Class OR n:Enum OR n:Annotation) " + 
				"AND NOT n:Anonymous AND NOT (n)<-[:CONTAINS]-(:Method) " +
				"RETURN n";
	}

	private String collectAllFields() {
		return "MATCH (n:Field)<-[:DECLARES]-(f:Type) " +
				"WHERE (NOT n.name CONTAINS '$') AND (NOT f:Anonymous) RETURN DISTINCT n";
	}

	private String collectAllMethods() {
		return "MATCH (n:Method)<-[:DECLARES]-(f:Type) " +
				"WHERE (NOT n.name CONTAINS '$') AND (NOT f:Anonymous) RETURN DISTINCT n";
	}

	private void enhanceNode(Record record) {
		Node node = record.get("n").asNode();
		Value fqnValue = node.get("fqn");
		String fqn = fqnValue.asString();
		if (fqnValue.isNull()) {
			Node container = connector.executeRead(
				"MATCH (n)<-[:DECLARES]-(container) " +
				"WHERE ID(n) = " + node.id() + " " +
				"RETURN container"
			).single().get("container").asNode();
			String containerFqn = container.get("fqn").asString();
			String name = node.get("name").asString();
			String signature = node.get("signature").asString();
			int index = signature.indexOf(" ") + 1;
			if (node.hasLabel("Method")) {
				int indexOfBracket = signature.indexOf("(");
				if (name.isEmpty()) {
					name = signature.substring(index, indexOfBracket);
				}
				fqn = containerFqn + "." + signature.substring(index);
			} else {
				if (name.isEmpty()) {
					name = signature.substring(index);
				}
				fqn = containerFqn + "." + name;
			}
			connector.executeWrite(
				"MATCH (n) WHERE ID(n) = " + node.id() + " SET n.name = \'" + name + "\', n.fqn = \'" + fqn + "\'");
		}
		connector.executeWrite("MATCH (n) WHERE ID(n) = " + node.id() + " SET n.hash = \'" + createHash(fqn) + "\'");
	}

}
