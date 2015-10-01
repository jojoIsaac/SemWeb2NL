package org.aksw.assessment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.aksw.assessment.answer.Answer;
import org.aksw.assessment.answer.SimpleAnswer;
import org.aksw.assessment.util.RDFNodeComparator;
import org.aksw.avatar.Verbalizer;
import org.aksw.avatar.clustering.hardening.HardeningFactory;
import org.aksw.avatar.clustering.hardening.HardeningFactory.HardeningType;
import org.aksw.avatar.dataset.DatasetBasedGraphGenerator.Cooccurrence;
import org.aksw.jena_sparql_api.core.QueryExecutionFactory;
import org.aksw.jena_sparql_api.http.QueryExecutionFactoryHttp;
import org.aksw.jena_sparql_api.utils.TripleUtils;
import org.aksw.sparql2nl.naturallanguagegeneration.SimpleNLGwithPostprocessing;
import org.aksw.sparql2nl.naturallanguagegeneration.TriplePatternConverter;
import org.aksw.sparqltools.util.SPARQLEndpointType;
import org.aksw.sparqltools.util.SPARQLQueryUtils;
import org.aksw.triple2nl.DefaultIRIConverter;
import org.aksw.triple2nl.LiteralConverter;
import org.aksw.triple2nl.TripleConverter;
import org.apache.log4j.Logger;
import org.dllearner.kb.sparql.SparqlEndpoint;
import org.dllearner.reasoning.SPARQLReasoner;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLObjectProperty;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.hp.hpl.jena.graph.NodeFactory;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;

import simplenlg.features.Feature;
import simplenlg.features.InternalFeature;
import simplenlg.features.InterrogativeType;
import simplenlg.framework.LexicalCategory;
import simplenlg.framework.NLGElement;
import simplenlg.framework.NLGFactory;
import simplenlg.lexicon.Lexicon;
import simplenlg.phrasespec.NPPhraseSpec;
import simplenlg.phrasespec.SPhraseSpec;
import simplenlg.realiser.english.Realiser;
import uk.ac.manchester.cs.owl.owlapi.OWLClassImpl;
import uk.ac.manchester.cs.owl.owlapi.OWLNamedIndividualImpl;
import uk.ac.manchester.cs.owl.owlapi.OWLObjectPropertyImpl;

/**
 * A generator for multiple choice questions.
 * @author Lorenz Buehmann
 * @author Axel Ngonga
 */
public class MultipleChoiceQuestionGenerator implements QuestionGenerator {

	private static final Logger logger = Logger.getLogger(MultipleChoiceQuestionGenerator.class.getName());
    static int DIFFICULTY = 1;
    
    protected SimpleNLGwithPostprocessing nlg;
    
    private LiteralConverter literalConverter;
    protected int maxNrOfAnswersPerQuestion = 5;
    
    private QueryExecutionFactory qef;
    private SPARQLReasoner reasoner;
    
    //configuration of verbalizer
    protected Verbalizer verbalizer; 
    final int maxShownValuesPerProperty = 3;
    protected double propertyFrequencyThreshold = 0.2; 
    protected Cooccurrence cooccurrenceType = Cooccurrence.PROPERTIES;
    protected HardeningType hardeningType = HardeningFactory.HardeningType.SMALLEST;
    protected String namespace;
    
    protected BlackList blackList;
    
	protected Map<OWLClass, Set<OWLObjectProperty>> restrictions;
	protected Set<RDFNode> usedWrongAnswers;
	
	protected SPARQLEndpointType endpointType = SPARQLEndpointType.Virtuoso;
	private TripleConverter tripleConverter;
	private NLGFactory nlgFactory;
	private Realiser realiser;
	private Set<String> personTypes;
	
	private boolean generateHints = false;
	
	public MultipleChoiceQuestionGenerator(QueryExecutionFactory qef, String cacheDirectory, String namespace,
			Map<OWLClass, Set<OWLObjectProperty>> restrictions, Set<String> personTypes, BlackList blackList) {
		this.qef = qef;
		this.namespace = namespace;
		this.restrictions = restrictions;
		this.personTypes = personTypes;
		this.blackList = blackList;
		
        literalConverter = new LiteralConverter(new DefaultIRIConverter(qef, cacheDirectory));
        
        reasoner = new SPARQLReasoner(qef);
        
        String wordNetDir = "wordnet/" + (SimpleNLGwithPostprocessing.isWindows() ? "windows" : "linux") + "/dict";
        wordNetDir = this.getClass().getClassLoader().getResource(wordNetDir).getPath();
        System.setProperty("wordnet.database.dir", "/home/rusbeck/wordnet/linux/dict");
        
        tripleConverter = new TripleConverter(qef, cacheDirectory, wordNetDir);
        
        Lexicon lexicon = Lexicon.getDefaultLexicon();
        nlgFactory = new NLGFactory(lexicon);
        realiser = new Realiser(lexicon);
 
        verbalizer = new JeopardyVerbalizer(qef, cacheDirectory, wordNetDir);
        verbalizer.setPersonTypes(personTypes);
        verbalizer.setMaxShownValuesPerProperty(maxShownValuesPerProperty);
        verbalizer.setOmitContentInBrackets(true);
        
        nlg = verbalizer.nlg;
    }
    
    private String selectQuestionProperty(Resource r, OWLClass type) {
    	// generate the property candidates
    	Set<String> propertyCandidates = new HashSet<String>();
    	
    	 // if there are no restrictions on the class we randomly select a property based on the existing instance data
        if(restrictions.get(type).isEmpty()){
			// first of all, we check if there exists any meaningful information about the given resource, 
			// i.e. whether there are interesting triples
			// this is done by getting all properties for the given resource and filter out the black listed ones
            logger.info("Getting property candidates...");
            String query = "select distinct ?p where {<" + r.getURI() + "> ?p ?o. }";//FILTER(isURI(?o))
            
            ResultSet rs = executeSelectQuery(query);
         
            while (rs.hasNext()) {
            	QuerySolution qs = rs.next();
                String property = qs.getResource("p").getURI();
                // filter by black list
                if (!GeneralPropertyBlackList.getInstance().contains(property)) {
                	propertyCandidates.add(property);
                }
            }
        } else {
        	for (OWLObjectProperty p : restrictions.get(type)) {
				propertyCandidates.add(p.toStringID());
			}
        }
        logger.info("Property candidates: " + propertyCandidates);
        
        // early termination if resource has no meaningful properties
        if (propertyCandidates.isEmpty()) {
            return null;
        }
        
        // random selection
        Random rnd = new Random();
        String property = propertyCandidates.toArray(new String[]{})[rnd.nextInt(propertyCandidates.size())];
        logger.info("Randomly chosen property: " + property);
        
        return property;
    }
    
    private Set<RDFNode> generateCorrectAnswerCandidates(Resource r, String property) {
    	 logger.info("Generating correct answers...");
    	 Set<RDFNode> correctAnswers = new TreeSet<RDFNode>(new RDFNodeComparator());
    	 
    	// get values for property, i.e. the correct answers
        String query = "select distinct ?o where {<" + r.getURI() + "> <" + property + "> ?o}";
        ResultSet rs = executeSelectQuery(query);
        
        while (rs.hasNext()) {
        	QuerySolution qs = rs.next();
            if (qs.get("o").isLiteral()) {
                correctAnswers.add(qs.get("o").asLiteral());
            } else {
                correctAnswers.add(qs.get("o").asResource());
            }
        }
        logger.info("...got " + correctAnswers);
        
        return correctAnswers;
    }
    
    private Set<RDFNode> generateWrongAnswerCandidates(Resource r, String property, Set<RDFNode> correctAnswers) {
        logger.info("Generating wrong answers...");
        Set<RDFNode> wrongAnswers = new TreeSet<RDFNode>(new RDFNodeComparator());
        
        // get similar of nature but wrong answers by using resources in object position using the same property as for the correct answers
        // TODO: some ranking for the wrong answers could be done in the same way as for the subjects
		String query = "select distinct ?o where {?x <" + property + "> ?o. } LIMIT 1000";
		ResultSet rs = executeSelectQuery(query);
		while (rs.hasNext()) {
			QuerySolution qs = rs.next();
			RDFNode node = qs.get("o");
			
			if (!correctAnswers.contains(node)) {
				wrongAnswers.add(node);
			}
		}
		return wrongAnswers;
    }
    
    private boolean isPersonType(OWLClass cls) {
    	for (String type : personTypes) {
    		if(cls.toStringID().equals(type)) {
    			return true;
    		} else if(reasoner.isSuperClassOf(new OWLClassImpl(IRI.create(type)), cls)) {
				return true;
			}
		}
    	return false;
    }
    
    private String generateQuestionText(Resource r, String property) {
    	logger.info("Generating question...");
    	
    	Triple t = Triple.create(r.asNode(), NodeFactory.createURI(property), NodeFactory.createURI(""));
    	
    	// convert to phrase
    	SPhraseSpec p = tripleConverter.convertTriple(t);
    	
    	// decide whether to use 'what' or 'who'
    	InterrogativeType interrogativeType = InterrogativeType.WHAT_OBJECT;
    	
    	// get range of property
    	OWLClassExpression range = reasoner.getRange(new OWLObjectPropertyImpl(IRI.create(property)));
    	if(isPersonType(range.asOWLClass())) {
    		interrogativeType = InterrogativeType.WHO_OBJECT;
    	}
		
		p.setFeature(Feature.INTERROGATIVE_TYPE, interrogativeType);
		String question = realiser.realiseSentence(p);
		
        
//        NLGElement subjectElement = nlgFactory.createNLGElement("entity", LexicalCategory.NOUN);
//		NLGElement objectElement = nlgFactory.createNLGElement(tp.getObject().toString(), LexicalCategory.NOUN);
//		
//		NPPhraseSpec phrase = tripleConverter.convertTriplePattern(tp, subjectElement, objectElement, true, false, true);
//		List<NLGElement> clauses = phrase.getFeatureAsElementList(InternalFeature.COMPLEMENTS);
//		String question = realiser.realise(clauses.get(0)).getRealisation();
		
//		boolean functional = reasoner.isFunctional(new OWLObjectPropertyImpl(IRI.create(property)));
//		
//        String[] questionHeaders = new String[]{"What are", "Please select", "Please choose"};
//        question = questionHeaders[0] + " " + question;
        logger.info("Question:" + question);
        return question;
    }

    public Question generateQuestion(Resource r, OWLClass type) {
        logger.info("Generating question for resource " + r + "...");
        
        // select the property used in the question
        String property = selectQuestionProperty(r, type);
        
        // generate the question text
        String questionText = generateQuestionText(r, property);

        // generate correct answers
        Set<RDFNode> correctAnswers = generateCorrectAnswerCandidates(r, property);
        
        // if there is no correct answer, we stop
        if(correctAnswers.isEmpty()){
        	logger.warn("Could not find a correct answer.");
        	return null;
        }
        
        // generate wrong answer candidates
        Set<RDFNode> wrongAnswerCandidates = generateWrongAnswerCandidates(r, property, correctAnswers);
        wrongAnswerCandidates.removeAll(usedWrongAnswers);
        
        // we pick at least 1 and at most N correct answers randomly
        Random rnd = new Random(123);
        List<RDFNode> correctAnswerList = new ArrayList<RDFNode>(correctAnswers);
        Collections.shuffle(correctAnswerList, rnd);
        int maxNumberOfCorrectAnswers = rnd.nextInt((maxNrOfAnswersPerQuestion - 1) + 1) + 1;
        correctAnswerList = correctAnswerList.subList(0, Math.min(correctAnswerList.size(), maxNumberOfCorrectAnswers));
        
        // we pick (N - #correctAnswers) wrong answers randomly
        rnd = new Random(123);
        List<RDFNode> wrongAnswerList = new ArrayList<RDFNode>(wrongAnswerCandidates);
        Collections.shuffle(wrongAnswerList, rnd);
        wrongAnswerList = wrongAnswerList.subList(0, Math.min(wrongAnswerList.size(), maxNrOfAnswersPerQuestion - correctAnswerList.size()));
        usedWrongAnswers.addAll(wrongAnswerList);
        logger.info("...got " + wrongAnswerList);
        
        return new SimpleQuestion(
        		questionText, 
        		generateAnswers(correctAnswerList, generateHints), 
        		generateAnswers(wrongAnswerList, false), 
        		DIFFICULTY, 
        		null, 
        		QuestionType.MC);
    }
    
    @Override
    public Set<Question> getQuestions(Map<Triple, Double> informativenessMap, int difficulty, int numberOfQuestions) {
    	 usedWrongAnswers = new HashSet<>();
        Set<Question> questions = new HashSet<>();
        
        // 1. we generate possible resources
        Map<Resource, OWLClass> resources = getMostProminentResources(restrictions.keySet());
        
        //  2. we generate question(s) as long as we have resources or we got the maximum number of questions
        //  Collections.shuffle(resources, new Random(123));
        Iterator<Entry<Resource, OWLClass>> iterator = resources.entrySet().iterator();
        
        while(questions.size() < numberOfQuestions && iterator.hasNext()){
        	Entry<Resource, OWLClass> entry = iterator.next();
        	
        	Resource ind = entry.getKey();
        	OWLClass cls = entry.getValue();
        	
        	Question q = generateQuestion(ind, cls);
            if (q != null) {
                questions.add(q);
            } else {
            	logger.warn("Could not generate question.");
            }
        }
        
        return questions;
    }
    
    /**
	 * @param restrictions the restrictions to set
	 */
	public void setRestrictions(Map<OWLClass, Set<OWLObjectProperty>> restrictions) {
		this.restrictions = restrictions;
	}
    
	protected ResultSet executeSelectQuery(String query) {
		logger.debug("Sending query \n" + query);
        QueryExecution qe = qef.createQueryExecution(query);
        ResultSet rs = null;
        try {
            rs = qe.execSelect();
        } catch (Exception e) {
            logger.error("Error when executing query\n" + query, e);
        }
        return rs;
    }

    protected List<Answer> generateAnswers(Collection<RDFNode> resources, boolean addHint) {
		List<Answer> answers = new ArrayList<Answer>();
		
		for (RDFNode node : resources) {
			// get a textual representation of the resource
			String textualRepresentation = getTextualRepresentation(node);
			
			// if answer is a resource, additionally generate a summary which can be used as hint 
			String hint = null;
			if (addHint && node.isURIResource()) {
				hint = generateHint(node.asResource());
			}
			Answer answer = new SimpleAnswer(textualRepresentation, hint);
			answers.add(answer);
		}
		return answers;
	}
    
    protected String generateHint(Resource r){
    	String hint = getEntitySummary(r.getURI());
    	return hint;
    }
    
    /**
     * Generate textual representation of RDFNode object.
     * @param node
     * @return
     */
    protected String getTextualRepresentation(RDFNode node){
    	String s;
    	if (node.isURIResource()) {
			s = nlg.realiser.realise(nlg.getNPPhrase(node.asResource().getURI(), false, false)).getRealisation();
		} else if (node.isLiteral()) {
			s = literalConverter.convert(node.asLiteral());
		} else {
			throw new IllegalArgumentException("Conversion of blank node " + node + " not supported yet!");
		}
    	return s;
    }
    
    /**
     * Returns a textual summary for a given individual.
     * @param ind
     * @return
     */
	protected String getEntitySummary(String entityURI) {
		//get the most specific type(s) of the individual
		Set<OWLClass> mostSpecificTypes = getMostSpecificTypes(entityURI);
		
		// pick the most prominent type
		if (mostSpecificTypes.iterator().hasNext()) {
			// return the summary
			OWLClass mostSpecificType = mostSpecificTypes.iterator().next();
			return getEntitySummary(entityURI, mostSpecificType);
		} else {
			return "No hint available";
		}
	}
    
    /**
     * Returns a textual summary for a given individual and its type.
     * @param ind
     * @return
     */
	protected String getEntitySummary(String entityURI, OWLClass type) {
		logger.info("Generating summary for " + entityURI + " of type " + type + "...");
		
		// create the summary
		OWLNamedIndividualImpl ind = new OWLNamedIndividualImpl(IRI.create(entityURI));
		List<NLGElement> text = verbalizer.verbalize(ind, type, namespace, propertyFrequencyThreshold, cooccurrenceType, hardeningType);
		if (text == null)
			return null;
		String summary = verbalizer.realize(text);
		if (summary == null)
			return null;
		summary = summary.replaceAll("\\s?\\((.*?)\\)", "");
		summary = summary.replace(" , among others,", ", among others,");
		logger.info("...finished generating summary.");
		return summary;
	}
	
	 protected Map<Resource, OWLClass> getMostProminentResources(Set<OWLClass> types) {
        logger.info("Getting possible resources for types " + types + " ranked by prominence...");
        if (types == null || types.isEmpty()) {
            return null;
        }
        
        Map<Resource, OWLClass> result = Maps.newLinkedHashMap();
        for (OWLClass type : types) {
        	StringBuilder query = new StringBuilder();
        	query.append("SELECT DISTINCT ?x WHERE{");
        	query.append("?x a <" + type.toStringID() + ">.");
        	query.append("?x ?p ?o . VALUES ?p {" + Joiner.on(" ").join(restrictions.get(type)) + "}");
        	SPARQLQueryUtils.addRankingConstraints(endpointType, query, "x");
        	query.append("}");
        	SPARQLQueryUtils.addRankingOrder(endpointType, query, "x");
            query.append(" LIMIT 500");
            ResultSet rs = executeSelectQuery(query.toString());
            QuerySolution qs;
            while (rs.hasNext()) {
                qs = rs.next();
                result.put(qs.getResource("x"), type);
            }
		}
//        String query = "SELECT distinct ?x (COUNT(?s) AS ?cnt) WHERE {?s ?p ?x. ";
//		for (OWLClass nc : types) {
//			query = query + "{?x a <" + nc.getURI() + ">} UNION ";
//		}
//		query = query.substring(0, query.lastIndexOf("UNION"));
//        query += "} ORDER BY DESC(?cnt) LIMIT 500";
//        Collections.shuffle(result);
        logger.info("...got " + result.size() + " resources, e.g. " + new ArrayList<Resource>(result.keySet()).subList(0, Math.min(10, result.size())));
        return result;
    }
	
	protected Set<OWLClass> getMostSpecificTypes(String uri) {
		Set<OWLClass> types = reasoner.getMostSpecificTypes(new OWLNamedIndividualImpl(IRI.create(uri)));
		for (Iterator<OWLClass> iter = types.iterator(); iter.hasNext();) {
			OWLClass cls = iter.next();
			if (namespace != null && !cls.toStringID().startsWith(namespace)) {
				iter.remove();
			}
		}
		return types;
	}

    protected Collection<Triple> getSummaryTriples(String entityURI){
		return verbalizer.getSummaryTriples(new OWLNamedIndividualImpl(IRI.create(entityURI)));
	}
    
    /**
	 * @param blackList the blackList to set
	 */
	public void setBlackList(BlackList blackList) {
		this.blackList = blackList;
	}
	
	/**
	 * @param generateHints whether to generate hints in forms if short
	 *            descriptions for the correct answers
	 */
	public void setGenerateHints(boolean generateHints) {
		this.generateHints = generateHints;
	}
    
    public static void main(String args[]) throws Exception{
        Map<OWLClass, Set<OWLObjectProperty>> restrictions = Maps.newHashMap();
        restrictions.put(
//        		new OWLClassImpl(IRI.create("http://www4.wiwiss.fu-berlin.de/diseasome/resource/diseasome/diseases")),
//        		new HashSet<OWLObjectProperty>()
        		new OWLClassImpl(IRI.create("http://dbpedia.org/ontology/Person")), 
        		Sets.<OWLObjectProperty>newHashSet(
        				new OWLObjectPropertyImpl(IRI.create("http://dbpedia.org/ontology/birthPlace")), 
        				new OWLObjectPropertyImpl(IRI.create("http://dbpedia.org/ontology/birthDate")),
        				new OWLObjectPropertyImpl(IRI.create("http://dbpedia.org/ontology/knownFor")),
        				new OWLObjectPropertyImpl(IRI.create("http://dbpedia.org/ontology/influencedBy"))
        				)
        );
        SparqlEndpoint endpoint = SparqlEndpoint.getEndpointDBpedia();
        QueryExecutionFactory qef = new QueryExecutionFactoryHttp("http://sake.informatik.uni-leipzig.de:8890/sparql", "http://dbpedia.org");
//        MultipleChoiceQuestionGenerator sqg = new MultipleChoiceQuestionGenerator(
//        		new SparqlEndpoint(new URL("http://vtentacle.techfak.uni-bielefeld.de:443/sparql"), "http://biomed.de/diseasome"), 
//        		"cache", 
//        		"http://www4.wiwiss.fu-berlin.de/diseasome/resource/diseasome/", 
//        		restrictions,
//        		new HashSet<String>(), null);
        		MultipleChoiceQuestionGenerator sqg = new MultipleChoiceQuestionGenerator(
        				qef, 
        				"cache", "http://dbpedia.org/ontology/", 
        				restrictions,
        				Sets.newHashSet("http://dbpedia.org/ontology/Person"), new DBpediaPropertyBlackList());
        		long start = System.currentTimeMillis();
        		Set<Question> questions = sqg.getQuestions(null, DIFFICULTY, 20);
        		long end = System.currentTimeMillis();
        		System.out.println("Operation took " + (end - start) + "ms");
        		
        for (Question q : questions) {
            if (q != null) {
                System.out.println(">>" + q.getText());
                List<Answer> correctAnswers = q.getCorrectAnswers();
                System.out.println(correctAnswers);
                List<Answer> wrongAnswers = q.getWrongAnswers();
                System.out.println(wrongAnswers);
            }
        }
    }
}
