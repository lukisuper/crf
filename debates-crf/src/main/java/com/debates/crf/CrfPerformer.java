package com.debates.crf;

import com.debates.crf.exception.CorpusCreationException;
import com.debates.crf.stemming.MyWordStemmer;
import com.debates.crf.utils.TextWithAnnotations;
import com.jjlteam.domain.Document;
import com.jjlteam.domain.Proposition;
import com.jjlteam.domain.Reason;
import com.jjlteam.parser.BratParser;
import morfologik.stemming.polish.PolishStemmer;
import org.apache.commons.io.IOUtils;
import org.crf.crf.CrfModel;
import org.crf.crf.run.CrfInferencePerformer;
import org.crf.utilities.TaggedToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.rmi.runtime.Log;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by lukasz on 14.04.16.
 */
public class CrfPerformer {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    /**
     * performs CRF on test data (testTwa) using training data(trainingTwas)
     *
     * @param trainingTwas
     * @param testTwa
     * @throws IOException
     * @throws CorpusCreationException
     */
    @SuppressWarnings("unchecked")
    public static void perform(List<TextWithAnnotations> trainingTwas,
                               TextWithAnnotations testTwa) throws IOException, CorpusCreationException {

        if (trainingTwas.isEmpty()) {
            LOGGER.info("No training data");
            return;
        }

        // prepare training corpus
        List<List<? extends TaggedToken<String, String>>> corpus = new ArrayList<>();
        for (TextWithAnnotations twa : trainingTwas) {
            corpus.addAll(createCorpus(twa));
        }

        int i = 0;


    // Create trainer factory
    DebateCrfTrainerFactory<String, String> trainerFactory = new DebateCrfTrainerFactory<>();

    // Create trainer
    DebateCrfTrainer<String, String> trainer = trainerFactory.createTrainer(
            corpus,
            new DebateCrfFeatureGeneratorFactory(),
            new DebateFilterFactory());

    // Run training with the loaded corpus.
    trainer.train(corpus);

    // Get the model
    CrfModel<String, String> crfModel = trainer.getLearnedModel();

//        // Save the model into the disk.
//        File file = new File("example.ser");
//        save(crfModel,file);
//
//        ////////
//
//        // Later... Load the model from the disk
//        crfModel = (CrfModel<String, String>) load(file);

    // Create a CrfInferencePerformer, to find tags for test data
    CrfInferencePerformer<String, String> inferencePerformer = new CrfInferencePerformer<>(crfModel);

    //  create test corpus
    List<List<? extends TaggedToken<String, String>>> testCorpus = createCorpus(testTwa);

    // infer tags
    for(List<? extends TaggedToken<String, String>> testSentence
    :testCorpus)

    {

        //  extract sentence from testSentence
        List<String> sentence = testSentence.stream().map(TaggedToken::getToken).collect(Collectors.toList());

        //  infer tags for the sentence
        List<TaggedToken<String, String>> result = inferencePerformer.tagSequence(sentence);

        print(result, testSentence);
    }

}



    /**
     * prints testSentence in format:
     *  WORD0(PREDICTED_TAG0, REAL_TAG0) WORD1(PREDICTED_TAG1, REAL_TAG1)
     * @param taggedSentence
     * @param testSentence
     */
    private static void print(List<TaggedToken<String, String>> taggedSentence,
                              List<? extends TaggedToken<String, String>> testSentence) {
        // Print the result:
        int i = 0;
        for (TaggedToken<String, String> taggedToken : taggedSentence) {
            System.out.print(taggedToken.getToken() +
                    "(" +
                    taggedToken.getTag().substring(0,2) +
                    "/" +
                    testSentence.get(i).getTag() +   //TODO get(i) potentially so inefficent..
                    ") ");
            ++i;
        }
        System.out.println();
    }

//    public static void save(Object object, File file)
//    {
//        try(ObjectOutputStream stream = new ObjectOutputStream(new FileOutputStream(file)))
//        {
//            stream.writeObject(object);
//        }
//        catch (IOException e)
//        {
//            throw new RuntimeException("Failed to save",e);
//        }
//    }
//
//    public static Object load(File file)
//    {
//        try(ObjectInputStream stream = new ObjectInputStream(new FileInputStream(file)))
//        {
//            return stream.readObject();
//        }
//        catch (ClassNotFoundException | IOException e)
//        {
//            throw new RuntimeException("Failed to load",e);
//        }
//    }


    /**
     * Transforms TextWithAnnotation into corpus, which is a tokenized String from TextWithAnnotation.textFile
     * tagged with com.debates.crf.Tag tags
     * @param twa
     * @return
     * @throws IOException
     * @throws CorpusCreationException
     */
    private static List<List<? extends TaggedToken<String, String>>> createCorpus (TextWithAnnotations twa)
            throws IOException, CorpusCreationException {

        //LOGGER.info("created");

        final String wordLetters = "[a-zA-Z0-9\u00F3\u0105" +
                "\u0119" + "\u0142" + "u\u017C" + "\u017A" + "\u0144" + "\u0107" + "\u015B" + "\u0104" +
                "\u0118" + "\u00D3" + "\u0141" + "\u0179" + "\u017B" + "\u0143" + "\u015A" + "\u0106" +
                "\\-" + "\\%" + "\u22ee]"; //...
        final String punctuationsEndSentence = "[./?!]";


        /** read propositions   **/

        File annFile = twa.getAnnotationsFile();
        Document parsedAnnDocument = BratParser.parse(annFile);

        List<Proposition> propositions = new ArrayList<>(parsedAnnDocument.getPropositions().values());
        List<Reason> reasons = new ArrayList<>(parsedAnnDocument.getReasons().values());

        //  sort propositions and reasons asc according to startIndex
        Collections.sort(propositions, (Proposition o1, Proposition o2) ->
                o1.getStartIndex().compareTo(o2.getStartIndex()));
        Collections.sort(reasons, (Reason o1, Reason o2) ->
                o1.getStartIndex().compareTo(o2.getStartIndex()));

        //  set currProposition as first one (with smallest start index)
        Iterator<Proposition> propositionsSortedIterator = propositions.iterator();
        Proposition currProposition = propositionsSortedIterator.hasNext() ? propositionsSortedIterator.next() : null;

        //  set currReason as first one (with smallest start index)
        Iterator<Reason> reasonSortedIterator = reasons.iterator();
        Reason currReason = reasonSortedIterator.hasNext() ? reasonSortedIterator.next() : null;

        /** read text file  **/

        MyWordStemmer myWordStemmer = new MyWordStemmer(new PolishStemmer());
        List<List<? extends TaggedToken<String, String>>> result = new LinkedList<>();

        File textFile = twa.getTextFile();
        String text = IOUtils.toString(new FileInputStream(textFile), "UTF8");

        List<TaggedToken<String, String>> currSequence = new ArrayList<>();

        // tag for previous stem is null
        Tag previousTag = null;
        // real index so we do not count \n and \r twice
        int realIdx =-1;
        for(int leftIdx = 0; leftIdx < text.length(); ++leftIdx) {

            // if there was punctuation mark at the end of the sentence
            boolean wasTherePunctuationMark = false;

            char currChar = text.charAt(leftIdx);


            if(currChar == '\n' || currChar == '\r') {
                if (currChar == '\n')
                    realIdx++;
                result.add(currSequence);
                currSequence = new ArrayList<>();
                continue;
            }

            if (currChar == ' ') {
                realIdx++;
                continue;
            }

            realIdx++;


            // JLL block of code(ive been given) - want to work on the same data, so processing needs to be quite the same

            // if the first letter is a word character or before a word character is a white character
            if(
                    leftIdx == 0 && Character.toString(text.charAt(leftIdx)).matches(wordLetters) ||
                            (
                                    leftIdx > 0 && Character.toString(text.charAt(leftIdx)).matches(wordLetters) &&
                                            !Character.toString(text.charAt(leftIdx - 1)).matches(wordLetters)
                            )
                    ) {
                int rightIdx = leftIdx + 1;
                for (; rightIdx < text.length(); ++rightIdx) {
                    if (!Character.toString(text.charAt(rightIdx)).matches(wordLetters)) {
                        // white character
                        if (Character.toString(text.charAt(rightIdx)).matches(punctuationsEndSentence) )
                            wasTherePunctuationMark = true;
                        break;
                    }
                }

                Tag tag = Tag.OTHER;

                //  is it proposition ?
                if(currProposition != null) {
                    if(realIdx > currProposition.getEndIndex()) {
                        if(propositionsSortedIterator.hasNext()) {
                            currProposition = propositionsSortedIterator.next();
                        } else {
                            currProposition = null;
                        }
                    }
                    if(currProposition != null) {

                        int compareRight = rightIdx - (leftIdx - realIdx);
                        if(realIdx >= currProposition.getStartIndex() &&
                                    compareRight == currProposition.getEndIndex()) {
                            tag = Tag.PROPOSITION_END;
                        } else if(realIdx == currProposition.getStartIndex() &&
                                compareRight <= currProposition.getEndIndex()) {
                            tag = Tag.PROPOSITION_START;
                        } else if (realIdx >= currProposition.getStartIndex() &&
                                compareRight + 1 == currProposition.getEndIndex()
                              && wasTherePunctuationMark == true &&
                                ((previousTag == Tag.PROPOSITION || previousTag == Tag.PROPOSITION_START)) ){
                            tag = Tag.PROPOSITION_END;
                        } else if(realIdx >= currProposition.getStartIndex() &&
                                compareRight < currProposition.getEndIndex()) {
                            tag = Tag.PROPOSITION;
                        }
                    }
                }

                //  is it reason?
                if(currReason != null) {
                    if(realIdx > currReason.getEndIndex()) {
                        if(reasonSortedIterator.hasNext()) {
                            currReason = reasonSortedIterator.next();
                        } else {
                            currReason = null;
                        }
                    }
                    if(currReason != null) {
                        int compareRight = rightIdx - (leftIdx - realIdx);
                        if(realIdx >= currReason.getStartIndex() &&
                                compareRight == currReason.getEndIndex()) {
                            tag = Tag.REASON_END;
                        } else if(realIdx == currReason.getStartIndex() &&
                                compareRight <= currReason.getEndIndex()) {
                            tag = Tag.REASON_START;
                        } else if (realIdx >= currReason.getStartIndex()
                                && compareRight + 1 == currReason.getEndIndex()
                                && wasTherePunctuationMark == true && ((previousTag == Tag.REASON || previousTag == Tag.REASON_START))) {
                            tag = Tag.REASON_END;
                        }
                            else if(realIdx >= currReason.getStartIndex() &&
                                    compareRight < currReason.getEndIndex()) {
                            tag = Tag.REASON;
                        }
                    }
                }

                previousTag = tag;
                final String selectedWord = text.substring(leftIdx, rightIdx).toLowerCase();
                final String stem = myWordStemmer.getStemNotNull(selectedWord);
                currSequence.add(new TaggedToken<>(stem, tag.name()));
            }
        }

        if(currProposition != null || currReason != null) {
            throw new CorpusCreationException("Not all the propositions or reasons were properly parsed");
        }

        List<List<? extends TaggedToken<String, String>>> filteredResult =
                result.stream().filter(taggedTokens -> !taggedTokens.isEmpty()).collect(Collectors.toList());

        return filteredResult;
    }

}
