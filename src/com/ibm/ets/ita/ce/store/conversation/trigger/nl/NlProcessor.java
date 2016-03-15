package com.ibm.ets.ita.ce.store.conversation.trigger.nl;

import static com.ibm.ets.ita.ce.store.utilities.FileUtilities.appendToSb;
import static com.ibm.ets.ita.ce.store.utilities.FileUtilities.appendToSbNoNl;

import java.util.ArrayList;

import com.ibm.ets.ita.ce.store.ActionContext;
import com.ibm.ets.ita.ce.store.conversation.model.ConvSentence;
import com.ibm.ets.ita.ce.store.conversation.model.ConvText;
import com.ibm.ets.ita.ce.store.conversation.model.ExtractedItem;
import com.ibm.ets.ita.ce.store.conversation.model.FinalItem;
import com.ibm.ets.ita.ce.store.conversation.model.ProcessedWord;
import com.ibm.ets.ita.ce.store.conversation.trigger.general.Card;
import com.ibm.ets.ita.ce.store.conversation.trigger.general.CardGenerator;
import com.ibm.ets.ita.ce.store.conversation.trigger.general.Concept;
import com.ibm.ets.ita.ce.store.conversation.trigger.general.GeneralProcessor;
import com.ibm.ets.ita.ce.store.conversation.trigger.general.Property;
import com.ibm.ets.ita.ce.store.conversation.trigger.general.Reply;
import com.ibm.ets.ita.ce.store.conversation.trigger.general.Word;
import com.ibm.ets.ita.ce.store.model.CeConcept;
import com.ibm.ets.ita.ce.store.model.CeInstance;
import com.ibm.ets.ita.ce.store.model.CeProperty;
import com.ibm.ets.ita.ce.store.model.CePropertyInstance;

public class NlProcessor extends GeneralProcessor {

    private ConvText convText;
    private NlTriggerHandler th;
    private NlAnswerGenerator ag;

    public NlProcessor(ActionContext ac, NlTriggerHandler th) {
        this.ac = ac;
        this.th = th;
        cg = new CardGenerator(ac);
        ag = new NlAnswerGenerator(ac);
    }

    // Process card
    public void process(CeInstance cardInst) {
        String nlText = cardInst.getSingleValueFromPropertyNamed(Property.CONTENT.toString());
        String modNlText = appendDotIfNeeded(nlText);
        System.out.println("text: " + modNlText);

        // Test for valid CE
        if (isValidCe(modNlText)) {
            // Valid CE
            cg.generateTellCard(cardInst, modNlText, th.getTriggerName(), th.getTellServiceName());
        } else {
            // NL
            convText = ConvText.createNewText(ac, nlText);

            ArrayList<CeInstance> matchingAgents = new ArrayList<CeInstance>();
            ArrayList<CeInstance> matchingKeywords = new ArrayList<CeInstance>();

            NlSentenceProcessor sp = new NlSentenceProcessor(ac, cardInst);
            NlQuestionProcessor qp = new NlQuestionProcessor();

            for (ConvSentence sentence : convText.getChildSentences()) {
                // Process sentence against bag of words
                ArrayList<ProcessedWord> words = sp.process(sentence);
                sp.extractMatchingEntities(sentence, words);
                ArrayList<FinalItem> finalItems = qp.getFinalItems(words);
                ArrayList<FinalItem> optionItems = qp.getOptionItems(words);
                ArrayList<FinalItem> maybeItems = qp.getMaybeItems(words);

                // Find agents matching keywords as defined in config
                findMatchingAgents(sentence.getSentenceText(), matchingAgents, matchingKeywords);

                if (matchingAgents.size() > 1) {
                    // Confirm which agent with user
                    askToConfirmAgent(cardInst, matchingAgents);
                } else if (matchingAgents.size() == 1) {
                    // Run agent
                    CeInstance agent = matchingAgents.get(0);
                    sendToAgent(agent, finalItems, matchingKeywords, cardInst, nlText);
                } else {
                    // No matching agents
                    if (fromTellService(cardInst)) {
                        // Reply from Tell
                        forwardTellResponse(cardInst, nlText);
                    } else if (isInterestingQuestion(nlText)) {
                        // TODO: Ignore interesting question?

                    } else if (convText.isQuestion()) {
                        // Respond to NL question
                        replyToNLQuestion(cardInst, finalItems, optionItems, maybeItems);
                    } else {
                        // Other NL - attempted fact sentence
                        interpretSentence(cardInst, finalItems);
                    }
                }
            }
        }
    }

    // Multiple agents have matched on sentence. Ask user to be more specific
    private void askToConfirmAgent(CeInstance cardInst, ArrayList<CeInstance> matchingAgents) {
        StringBuilder sb = new StringBuilder();
        appendToSbNoNl(sb, Reply.STATEMENT_MATCHES_MULTIPLE.toString());

        for (int i = 0; i < matchingAgents.size(); ++i) {
            CeInstance agent = matchingAgents.get(i);

            appendToSbNoNl(sb, agent.getInstanceName());

            if (i < matchingAgents.size() - 2) {
                appendToSbNoNl(sb, ",");
            } else if (i < matchingAgents.size() - 1) {
                appendToSbNoNl(sb, " and ");
            }
        }

        appendToSb(sb, Reply.AGENTS.toString());

        appendToSbNoNl(sb, Reply.BE_SPECIFIC.toString());
        String humanAgent = findHumanAgent(cardInst);
        cg.generateCard(Card.NL.toString(), sb.toString(), th.getTriggerName(), humanAgent, cardInst.getInstanceName());
    }

    // Matched on one agent. Use template to do agent processing
    private void sendToAgent(CeInstance agent, ArrayList<FinalItem> finalItems, ArrayList<CeInstance> matchingKeywords, CeInstance cardInst, String text) {
        ArrayList<CeInstance> templates = agent.getInstanceListFromPropertyNamed(ac, Property.TEMPLATE.toString());

        for (CeInstance template : templates) {
            boolean thingsFound;
            CeInstance matchingInstance = null;
            CeConcept matchingConcept = null;

            ArrayList<String> requiredThings = template.getValueListFromPropertyNamed(Property.MATCHING_THING.toString());
            if (!requiredThings.isEmpty()) {
                thingsFound = true;

                for (String requirement : requiredThings) {
                    if (requirement.equals(Word.INSTANCE.toString())) {
                        for (FinalItem item : finalItems) {
                            if (item.isInstanceItem()) {
                                CeInstance extractedInstance = item.getFirstExtractedItem().getInstance();

                                if (!matchingKeywords.contains(extractedInstance)) {
                                    matchingInstance = extractedInstance;
                                }
                            }
                        }

                        thingsFound = thingsFound && (matchingInstance != null);
                    } else if (requirement.equals(Word.CONCEPT.toString())) {
                        for (FinalItem item : finalItems) {
                            if (item.isConceptItem()) {
                                matchingConcept = item.getFirstExtractedItem().getConcept();
                            }
                        }

                        thingsFound = thingsFound && (matchingConcept != null);
                    }
                }
            } else {
                thingsFound = true;
            }

            if (thingsFound) {
                String templateString = template.getLatestValueFromPropertyNamed(Property.TEMPLATE_STRING.toString());
                String recipient = template.getLatestValueFromPropertyNamed(Property.RECIPIENT.toString());
                String reply = template.getLatestValueFromPropertyNamed(Property.REPLY.toString());
                String interestedUser = cardInst.getSingleValueFromPropertyNamed(Property.IS_FROM.toString());

                String completedRecipient = substituteItemsIntoTemplate(recipient, matchingInstance, matchingConcept, interestedUser, text);
                String completedTemplate = substituteItemsIntoTemplate(templateString, matchingInstance, matchingConcept, interestedUser, text);
                String completedReply = substituteItemsIntoTemplate(reply, matchingInstance, matchingConcept, interestedUser, text);

                cg.generateCard(Card.TELL.toString(), completedTemplate, th.getTriggerName(), completedRecipient, cardInst.getInstanceName());
                cg.generateCard(Card.NL.toString(), completedReply, th.getTriggerName(), interestedUser, cardInst.getInstanceName());
            }
        }
    }

    // Pass on Tell agent's message to human agent if other agent reply not already sent
    private void forwardTellResponse(CeInstance cardInst, String convText) {
        if (!templateAgentAlreadySentReply(cardInst) && convText.equals(Reply.SAVED.message())) {
            String humanAgent = findHumanAgent(cardInst);
            cg.generateNLCard(cardInst, convText, th.getTriggerName(), humanAgent, null);
        }
    }

    // NL Question found. Try and reply to matched instances, concepts and properties.
    private void replyToNLQuestion(CeInstance cardInst, ArrayList<FinalItem> finalItems, ArrayList<FinalItem> optionItems, ArrayList<FinalItem> maybeItems) {
        // TODO: Convert NL questions into CE queries and pass to Ask agent
        StringBuilder sb = new StringBuilder();

        if (optionItems != null && !optionItems.isEmpty()) {
            // Options are available so reply asking for clarification
            sb.append(ag.answerOptionQuestion(optionItems));
        } else if (maybeItems != null && !maybeItems.isEmpty()) {
            // Potential matching items available
            sb.append(ag.answerMaybeQuestion(maybeItems));
        } else if (finalItems != null && !finalItems.isEmpty()) {
            // Only final items available so answer with information about them
            // TODO: Compute who/what/where answers differently
            sb.append(ag.answerStandardQuestion(finalItems));
        }

        // If string builder is empty, then nothing has been understood
        if (sb.toString().isEmpty()) {
            sb.append(ag.nothingUnderstood());
        }

        ArrayList<String> referencedItems = new ArrayList<String>();
        ArrayList<CeInstance> referencedInsts = new ArrayList<CeInstance>();

        extractReferencedItems(finalItems, referencedItems, referencedInsts);

        // Generate NL Card with reply
        String humanAgent = findHumanAgent(cardInst);
        cg.generateNLCard(cardInst, sb.toString(), th.getTriggerName(), humanAgent, referencedItems);
    }

    private void interpretSentence(CeInstance cardInst, ArrayList<FinalItem> finalItems) {
        StringBuilder sb = new StringBuilder();

        if (finalItems != null && !finalItems.isEmpty()) {
            sb.append(ag.interpret(convText, finalItems));
        }

        // If string builder is empty, then nothing has been understood
        if (sb.toString().isEmpty()) {
            sb.append(ag.nothingUnderstood());
        }

        // Generate NL Card with reply
        String humanAgent = findHumanAgent(cardInst);
        cg.generateCard(Card.NL.toString(), sb.toString(), th.getTriggerName(), humanAgent, cardInst.getInstanceName());
    }

    // Find agents with matching keywords
    private void findMatchingAgents(String sentence, ArrayList<CeInstance> matchingAgents, ArrayList<CeInstance> matchingKeywords) {
        ArrayList<CeInstance> agents = ac.getModelBuilder().getAllInstancesForConceptNamed(ac, Concept.AGENT.toString());

        for (CeInstance agent : agents) {
            ArrayList<CeInstance> keywords = agent.getInstanceListFromPropertyNamed(ac, Property.KEYWORD.toString());

            for (CeInstance keyword : keywords) {
                // TODO: Do this using final items
                if (sentence.toLowerCase().contains(keyword.getInstanceName().toLowerCase())) {
                    matchingAgents.add(agent);
                    matchingKeywords.add(keyword);
                }
            }
        }
    }

    private boolean matchesAgents(String sentence) {
        ArrayList<CeInstance> agents = ac.getModelBuilder().getAllInstancesForConceptNamed(ac, Concept.AGENT.toString());

        for (CeInstance agent : agents) {
            ArrayList<CeInstance> keywords = agent.getInstanceListFromPropertyNamed(ac, Property.KEYWORD.toString());

            for (CeInstance keyword : keywords) {
                // TODO: Do this using final items
                if (sentence.toLowerCase().contains(keyword.getInstanceName().toLowerCase())) {
                    return true;
                }
            }
        }

        return false;
    }

    // Substitute mentioned instances, concepts, from user and original text into agent template
    private String substituteItemsIntoTemplate(String str, CeInstance instance, CeConcept concept, String user, String originalText) {
        if (instance != null) {
            str = str.replace("~ I ~", instance.getInstanceName());
        }
        if (concept != null) {
            str = str.replace("~ C ~", concept.getConceptName());
        }
        if (user != null) {
            str = str.replace("~ U ~", user);
        }
        if (originalText != null) {
            str = str.replace("~ S ~", originalText);
        }

        return str;
    }

    // Extract referenced items to set 'about' property in card
    private void extractReferencedItems(ArrayList<FinalItem> allFinalItems, ArrayList<String> referencedItems,
            ArrayList<CeInstance> referencedInsts) {
        for (FinalItem item : allFinalItems) {
            ArrayList<ExtractedItem> extractedItems = item.getExtractedItems();

            if (item.isPropertyInstanceItem()) {
                CeInstance instance = null;
                CeProperty property = null;
                ArrayList<CeProperty> properties = null;

                // Extract instance and property
                for (ExtractedItem extractedItem : extractedItems) {
                    if (extractedItem.isInstanceItem()) {
                        instance = extractedItem.getInstance();
                        referencedItems.add(instance.getInstanceName());
                    } else if (extractedItem.isPropertyItem()) {
                        properties = extractedItem.getPropertyList();
                    }
                }

                CeConcept[] instanceConcepts = instance.getDirectConcepts();

                // Find property that matches instance
                for (CeProperty prop : properties) {
                    CeConcept propertyConcept = prop.getDomainConcept();

                    for (CeConcept instanceConcept : instanceConcepts) {
                        if (instanceConcept.equals(propertyConcept)) {
                            property = prop;
                            break;
                        }
                    }
                }

                // Add value from property instance
                CePropertyInstance propertyInstance = instance.getPropertyInstanceForProperty(property);

                if (propertyInstance != null) {
                    String value = propertyInstance.getFirstPropertyValue().getValue();
                    referencedItems.add(value);
                }
            } else {
                for (ExtractedItem extractedItem : extractedItems) {
                    if (extractedItem.isInstanceItem()) {
                        referencedItems.add(extractedItem.getInstance().getInstanceName());
                    } else {
                        // TODO: do something with concepts and properties
                    }
                }
            }
        }
    }

    private CeInstance getLastHumanAgent(CeInstance cardInst) {
        CeInstance prevCard = cardInst;

        while (fromTellService(prevCard) || fromNLService(prevCard)) {
            if (prevCard == null) {
                return null;
            }

            prevCard = prevCard.getSingleInstanceFromPropertyNamed(ac, Property.IN_REPLY_TO.toString());
        }

        return prevCard;
    }

    // Find the last spoke to human agent from earlier in the conversation
    private String findHumanAgent(CeInstance cardInst) {
        CeInstance prevCard = getLastHumanAgent(cardInst);

        if (prevCard != null) {
            String humanAgent = prevCard.getSingleValueFromPropertyNamed(Property.IS_FROM.toString());
            return humanAgent;
        } else {
            return null;
        }
    }

    private boolean templateAgentAlreadySentReply(CeInstance cardInst) {
        CeInstance lastHumanCard = getLastHumanAgent(cardInst);
        return matchesAgents(lastHumanCard.getSingleValueFromPropertyNamed(Property.CONTENT.toString()));
    }

    private boolean fromNLService(CeInstance cardInst) {
        String fromService = cardInst.getSingleValueFromPropertyNamed(Property.IS_FROM.toString());
        return fromService.equals(th.getTriggerName());
    }

    private boolean fromTellService(CeInstance cardInst) {
        String fromService = cardInst.getSingleValueFromPropertyNamed(Property.IS_FROM.toString());
        return fromService.equals(th.getTellServiceName());
    }

    private boolean isInterestingQuestion(String text) {
        return text.contains(Reply.NEW_INTERESTING.toString());
    }

    // Trim leading and trailing whitespace and append full stop if needed
    private String appendDotIfNeeded(String text) {
        String result = text.trim();

        if (!result.endsWith(".")) {
            result += ".";
        }

        return result;
    }
}
