package editor.gui.inventory;

import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import editor.collection.Inventory;
import editor.database.card.AftermathCard;
import editor.database.card.Card;
import editor.database.card.CardLayout;
import editor.database.card.DoubleFacedCard;
import editor.database.card.FlipCard;
import editor.database.card.MeldCard;
import editor.database.card.SingleCard;
import editor.database.card.SplitCard;
import editor.database.characteristics.Expansion;
import editor.database.characteristics.Legality;
import editor.database.characteristics.ManaType;
import editor.database.characteristics.Rarity;
import editor.filter.leaf.options.multi.CardTypeFilter;
import editor.filter.leaf.options.multi.LegalityFilter;
import editor.filter.leaf.options.multi.SubtypeFilter;
import editor.filter.leaf.options.multi.SupertypeFilter;
import editor.gui.SettingsDialog;
import editor.util.UnicodeSymbols;

/**
 * This class represents a dialog that shows the progress for loading the
 * inventory and blocking the main frame until it is finished.
 *
 * @author Alec Roelke
 */
@SuppressWarnings("serial")
public class InventoryLoadDialog extends JDialog
{
    /**
     * This class represents a worker that loads cards from a JSON file in the background.
     * <p>
     * TODO: Use http://www.mtgsalvation.com/forums/magic-fundamentals/magic-software/494332-mtg-json-new-website-provides-mtg-card-data-in?page=21#c519 to generate card numbers for cards that don't have them
     *
     * @author Alec Roelke
     */
    private class InventoryLoadWorker extends SwingWorker<Inventory, String>
    {
        /**
         * File to load from.
         */
        private File file;

        /**
         * Create a new InventoryWorker.
         *
         * @param f #File to load
         */
        public InventoryLoadWorker(File f)
        {
            super();
            file = f;

            progressBar.setIndeterminate(true);
            addPropertyChangeListener((e) -> {
                if ("progress".equals(e.getPropertyName()))
                {
                    int p = (Integer)e.getNewValue();
                    progressBar.setIndeterminate(p < 0);
                    progressBar.setValue(p);
                }
            });
        }

        /**
         * {@inheritDoc}
         * Import a list of all cards that exist in Magic: the Gathering from a JSON file downloaded from
         * {@link "http://www.mtgjson.com"}.  Also populate the lists of types and expansions (and their blocks).
         *
         * @return The inventory of cards that can be added to a deck.
         */
        @Override
        protected Inventory doInBackground() throws Exception
        {
            publish("Opening " + file.getName() + "...");

            List<Card> cards = new ArrayList<>();
            Map<Card, List<String>> faces = new HashMap<>();
            Set<Expansion> expansions = new HashSet<>();
            Set<String> blockNames = new HashSet<>();
            Set<String> supertypeSet = new HashSet<>();
            Set<String> typeSet = new HashSet<>();
            Set<String> subtypeSet = new HashSet<>();
            Set<String> formatSet = new HashSet<>();

            // Read the inventory file
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF8")))
            {
                publish("Parsing " + file.getName() + "...");
                JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
                int numCards = 0;
                for (Map.Entry<String, JsonElement> setNode : root.entrySet())
                    numCards += setNode.getValue().getAsJsonObject().get("cards").getAsJsonArray().size();

                // TODO: Remove this when mtgjson fixes the numbering issue
                Map<Card, String> mciNumbers = new HashMap<>();
                publish("Reading cards from " + file.getName() + "...");
                setProgress(0);
                for (Map.Entry<String, JsonElement> setNode : root.entrySet())
                {
                    if (isCancelled())
                    {
                        expansions.clear();
                        blockNames.clear();
                        supertypeSet.clear();
                        typeSet.clear();
                        subtypeSet.clear();
                        formatSet.clear();
                        cards.clear();
                        return new Inventory();
                    }

                    // Create the new Expansion
                    JsonObject setProperties = setNode.getValue().getAsJsonObject();
                    JsonArray setCards = setProperties.get("cards").getAsJsonArray();
                    Expansion set = new Expansion(setProperties.get("name").getAsString(),
                            setProperties.has("block") ? setProperties.get("block").getAsString() : "<No Block>",
                            setProperties.get("code").getAsString(),
                            setProperties.get(setProperties.has("oldCode") ? "oldCode" : "code").getAsString(),
                            setProperties.get(setProperties.has("magicCardsInfoCode") ? "magicCardsInfoCode" : "code").getAsString().toUpperCase(),
                            setProperties.get(setProperties.has("gathererCode") ? "gathererCode" : "code").getAsString(),
                            setCards.size(),
                            LocalDate.parse(setProperties.get("releaseDate").getAsString(), Expansion.DATE_FORMATTER));
                    expansions.add(set);
                    blockNames.add(set.block);
                    publish("Loading cards from " + set + "...");

                    for (JsonElement cardElement : setCards)
                    {
                        // Create the new card for the expansion
                        JsonObject card = cardElement.getAsJsonObject();

                        // Card's name
                        String name = card.get("name").getAsString();

                        // If the card is a token, skip it
                        CardLayout layout;
                        try
                        {
                            layout = CardLayout.valueOf(card.get("layout").getAsString().toUpperCase().replaceAll("[^A-Z]", "_"));
                            if (layout == CardLayout.TOKEN)
                                continue;
                        }
                        catch (IllegalArgumentException e)
                        {
                            errors.add(name + " (" + set + "): " + e.getMessage());
                            continue;
                        }

                        // Card's mana cost
                        String mana = card.has("manaCost") ? card.get("manaCost").getAsString() : "";

                        // Card's set of colors (which is stored as a list, since order matters)
                        List<ManaType> colors = new ArrayList<>();
                        if (card.has("colors"))
                        {
                            JsonArray colorsArray = card.get("colors").getAsJsonArray();
                            for (JsonElement colorElement : colorsArray)
                                colors.add(ManaType.parseManaType(colorElement.getAsString()));
                        }

                        // Card's color identity
                        List<ManaType> colorIdentity = new ArrayList<>();
                        {
                            if (card.has("colorIdentity"))
                            {
                                JsonArray identityArray = card.get("colorIdentity").getAsJsonArray();
                                for (JsonElement identityElement : identityArray)
                                    colorIdentity.add(ManaType.parseManaType(identityElement.getAsString()));
                            }
                        }

                        // Card's set of supertypes
                        Set<String> supertypes = new LinkedHashSet<>();
                        if (card.has("supertypes"))
                        {
                            JsonArray superArray = card.get("supertypes").getAsJsonArray();
                            for (JsonElement superElement : superArray)
                            {
                                supertypes.add(superElement.getAsString());
                                supertypeSet.add(superElement.getAsString());
                            }
                        }

                        // Card's set of types
                        Set<String> types = new LinkedHashSet<>();
                        for (JsonElement typeElement : card.get("types").getAsJsonArray())
                        {
                            types.add(typeElement.getAsString());
                            typeSet.add(typeElement.getAsString());
                        }

                        // Card's set of subtypes
                        Set<String> subtypes = new LinkedHashSet<>();
                        if (card.has("subtypes"))
                        {
                            for (JsonElement subElement : card.get("subtypes").getAsJsonArray())
                            {
                                subtypes.add(subElement.getAsString());
                                subtypeSet.add(subElement.getAsString());
                            }
                        }

                        // Card's printed types
                        String printedTypes = card.has("originalType") ? card.get("originalType").getAsString() : "";

                        // Card's rarity
                        Rarity rarity = Rarity.parseRarity(card.get("rarity").getAsString());

                        // Card's rules text
                        String text = card.has("text") ? card.get("text").getAsString() : "";

                        // Card's flavor text
                        String flavor = card.has("flavor") ? card.get("flavor").getAsString() : "";

                        // Card's printed text
                        String printed = card.has("originalText") ? card.get("originalText").getAsString() : "";

                        // Card's artist
                        String artist = card.get("artist").getAsString();

                        // Card's number (this is a string since some don't have numbers or are things like "1a")
                        String number = card.has("number") ? card.get("number").getAsString() : "--";

                        // Card's power and toughness (empty if it doesn't have power or toughness)
                        String power = card.has("power") ? card.get("power").getAsString() : "";
                        String toughness = card.has("toughness") ? card.get("toughness").getAsString() : "";

                        // Card's loyalty (empty if it isn't a planeswalker or is Garruk, the Veil-Cursed)
                        String loyalty = "";
                        if (card.has("loyalty"))
                        {
                            JsonElement element = card.get("loyalty");
                            loyalty = element.isJsonNull() ? "X" : element.getAsString();
                        }

                        // Card's rulings
                        TreeMap<Date, List<String>> rulings = new TreeMap<>();
                        DateFormat format = new SimpleDateFormat("yyyy-MM-dd");
                        if (card.has("rulings"))
                        {
                            for (JsonElement l : card.get("rulings").getAsJsonArray())
                            {
                                JsonObject o = l.getAsJsonObject();
                                Date date = format.parse(o.get("date").getAsString());
                                String ruling = o.get("text").getAsString();
                                if (!rulings.containsKey(date))
                                    rulings.put(date, new ArrayList<>());
                                rulings.get(date).add(ruling);
                            }
                        }

                        // Card's legality in formats
                        Map<String, Legality> legality = new HashMap<>();
                        if (card.has("legalities"))
                        {
                            for (JsonElement l : card.get("legalities").getAsJsonArray())
                            {
                                JsonObject o = l.getAsJsonObject();
                                formatSet.add(o.get("format").getAsString());
                                legality.put(o.get("format").getAsString(), Legality.parseLegality(o.get("legality").getAsString()));
                            }
                        }

                        // Card's image name
                        String imageName = card.get("imageName").getAsString();

                        // Card's multiverseid
                        int multiverseid = card.has("multiverseid") ? card.get("multiverseid").getAsInt() : -1;

                        // Create the new card with all the values acquired above
                        Card c = new SingleCard(layout,
                                name,
                                mana,
                                colors,
                                colorIdentity,
                                supertypes,
                                types,
                                subtypes,
                                printedTypes,
                                rarity,
                                set,
                                text,
                                flavor,
                                printed,
                                artist,
                                multiverseid,
                                number,
                                power,
                                toughness,
                                loyalty,
                                rulings,
                                legality,
                                imageName);

                        // Add to map of faces if the card has multiple faces
                        if (layout.isMultiFaced)
                        {
                            List<String> names = new ArrayList<>();
                            for (JsonElement e : card.get("names").getAsJsonArray())
                                names.add(e.getAsString());
                            faces.put(c, names);
                        }

                        cards.add(c);
                        setProgress(cards.size() * 100 / numCards);
                        if (card.has("mciNumber"))
                            mciNumbers.put(c, card.get("mciNumber").getAsString());
                    }
                }

                publish("Processing multi-faced cards...");
                List<Card> facesList = new ArrayList<>(faces.keySet());
                while (!facesList.isEmpty())
                {
                    boolean error = false;

                    Card face = facesList.remove(0);
                    List<String> faceNames = faces.get(face);
                    List<Card> otherFaces = new ArrayList<>();
                    for (Card c : facesList)
                        if (faceNames.contains(c.unifiedName()) && c.expansion().equals(face.expansion()))
                            otherFaces.add(c);
                    facesList.removeAll(otherFaces);
                    otherFaces.add(face);
                    cards.removeAll(otherFaces);
                    // TODO: Fix this sorting when mtgjson fixes the numbering issue
                    if (face.layout() == CardLayout.MELD)
                        otherFaces.sort(Comparator.comparingInt((a) -> faceNames.indexOf(a.unifiedName())));
                    else
                        otherFaces.sort((a, b) -> {
                            if (mciNumbers.containsKey(a) && mciNumbers.containsKey(b))
                                return mciNumbers.get(a).compareToIgnoreCase(mciNumbers.get(b));
                            else
                                return faceNames.indexOf(a.unifiedName()) - faceNames.indexOf(b.unifiedName());
                        });
                    switch (face.layout())
                    {
                    case SPLIT:
                        if (otherFaces.size() < 2)
                        {
                            errors.add(face.toString() + " (" + face.expansion() + "): Can't find other face(s) for split card.");
                            error = true;
                        }
                        else
                        {
                            for (Card f : otherFaces)
                            {
                                if (f.layout() != CardLayout.SPLIT)
                                {
                                    errors.add(face.toString() + " (" + face.expansion() + "): Can't join non-split faces into a split card.");
                                    error = true;
                                }
                            }
                        }
                        if (!error)
                            cards.add(new SplitCard(otherFaces));
                        break;
                    case FLIP:
                        if (otherFaces.size() < 2)
                        {
                            errors.add(face.toString() + " (" + face.expansion() + "): Can't find other side of flip card.");
                            error = true;
                        }
                        else if (otherFaces.size() > 2)
                        {
                            errors.add(face.toString() + " (" + face.expansion() + "): Too many sides for flip card.");
                            error = true;
                        }
                        else if (otherFaces.get(0).layout() != CardLayout.FLIP || otherFaces.get(1).layout() != CardLayout.FLIP)
                        {
                            errors.add(face.toString() + " (" + face.expansion() + "): Can't join non-flip faces into a flip card.");
                            error = true;
                        }
                        if (!error)
                            cards.add(new FlipCard(otherFaces.get(0), otherFaces.get(1)));
                        break;
                    case DOUBLE_FACED:
                        if (otherFaces.size() < 2)
                        {
                            errors.add(face.toString() + " (" + face.expansion() + "): Can't find other face of double-faced card.");
                            error = true;
                        }
                        else if (otherFaces.size() > 2)
                        {
                            errors.add(face.toString() + " (" + face.expansion() + "): Too many faces for double-faced card.");
                            error = true;
                        }
                        else if (otherFaces.get(0).layout() != CardLayout.DOUBLE_FACED || otherFaces.get(1).layout() != CardLayout.DOUBLE_FACED)
                        {
                            errors.add(face.toString() + " (" + face.expansion() + "): Can't join single-faced cards into double-faced cards.");
                            error = true;
                        }
                        if (!error)
                            cards.add(new DoubleFacedCard(otherFaces.get(0), otherFaces.get(1)));
                        break;
                    case MELD:
                        if (otherFaces.size() < 3)
                        {
                            errors.add(face.toString() + " (" + face.expansion() + "): Can't find some faces of meld card.");
                            error = true;
                        }
                        else if (otherFaces.size() > 3)
                        {
                            errors.add(face.toString() + " (" + face.expansion() + "): Too many faces for meld card.");
                            error = true;
                        }
                        else if (otherFaces.get(0).layout() != CardLayout.MELD || otherFaces.get(1).layout() != CardLayout.MELD || otherFaces.get(2).layout() != CardLayout.MELD)
                        {
                            errors.add(face.toString() + " (" + face.expansion() + "): Can't join single-faced cards into meld cards.");
                            error = true;
                        }
                        if (!error)
                        {
                            cards.add(new MeldCard(otherFaces.get(0), otherFaces.get(1), otherFaces.get(2)));
                            cards.add(new MeldCard(otherFaces.get(1), otherFaces.get(0), otherFaces.get(2)));
                        }
                        break;
                    case AFTERMATH:
                        if (otherFaces.size() < 2)
                        {
                            errors.add(face.toString() + " (" + face.expansion() + "): Can't find some faces of aftermath card.");
                            error = true;
                        }
                        else if (otherFaces.size() > 2)
                        {
                            errors.add(face + " (" + face.expansion() + "): Too many faces for aftermath card.");
                            error = true;
                        }
                        else if (otherFaces.get(0).layout() != CardLayout.AFTERMATH || otherFaces.get(1).layout() != CardLayout.AFTERMATH)
                        {
                            errors.add(face + " (" + face.expansion() + "): Can't join single-faced cards into aftermath cards.");
                            error = true;
                        }
                        if (!error)
                            cards.add(new AftermathCard(otherFaces.get(0), otherFaces.get(1)));
                        break;
                    default:
                        break;
                    }
                }

                // Store the lists of expansion and block names and types and sort them alphabetically
                Expansion.expansions = expansions.stream().sorted().toArray(Expansion[]::new);
                Expansion.blocks = blockNames.stream().sorted().toArray(String[]::new);
                SupertypeFilter.supertypeList = supertypeSet.stream().sorted().toArray(String[]::new);
                CardTypeFilter.typeList = typeSet.stream().sorted().toArray(String[]::new);
                SubtypeFilter.subtypeList = subtypeSet.stream().sorted().toArray(String[]::new);
                LegalityFilter.formatList = formatSet.stream().sorted().toArray(String[]::new);
            }

            Inventory inventory = new Inventory(cards);
            if (SettingsDialog.getAsString(SettingsDialog.CARD_TAGS) != null)
            {
                Matcher m = Pattern.compile("\\((.*?)::\\[(.*?)\\]\\)").matcher(SettingsDialog.getAsString(SettingsDialog.CARD_TAGS));
                while (m.find())
                    Card.tags.put(inventory.get(m.group(1)), Arrays.stream(m.group(2).split(",")).collect(Collectors.toSet()));
            }
            return inventory;
        }

        /**
         * {@inheritDoc}
         * Close the dialog and allow it to return the Inventory
         * that was created.
         */
        @Override
        protected void done()
        {
            setVisible(false);
            dispose();
            if (!SettingsDialog.getAsBoolean(SettingsDialog.SUPPRESS_LOAD_WARNINGS) && !errors.isEmpty())
                SwingUtilities.invokeLater(() -> {
                    StringJoiner join = new StringJoiner("\n" + UnicodeSymbols.BULLET + " ");
                    join.add("Errors ocurred while loading the following card(s):");
                    for (String failure : errors)
                        join.add(failure);
                    JOptionPane.showMessageDialog(null, join.toString(), "Warning", JOptionPane.WARNING_MESSAGE);
                });
        }

        /**
         * {@inheritDoc}
         * Change the label in the dialog to match the stage this worker is in.
         */
        @Override
        protected void process(List<String> chunks)
        {
            for (String chunk : chunks)
            {
                progressLabel.setText(chunk);
                progressArea.append(chunk + "\n");
            }
        }
    }

    /**
     * List of errors that occurred while loading cards.
     */
    private List<String> errors;
    /**
     * Area showing past and current progress of loading.
     */
    private JTextArea progressArea;
    /**
     * Progress bar showing overall progress of loading.
     */
    private JProgressBar progressBar;
    /**
     * Label showing the current stage of loading.
     */
    private JLabel progressLabel;

    /**
     * Worker that loads the inventory.
     */
    private InventoryLoadWorker worker;

    /**
     * Create a new InventoryLoadDialog over the given {@link JFrame}.
     *
     * @param owner owner of the new InventoryLoadDialog
     */
    public InventoryLoadDialog(JFrame owner)
    {
        super(owner, "Loading Inventory", Dialog.ModalityType.APPLICATION_MODAL);
        setPreferredSize(new Dimension(350, 220));
        setResizable(false);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        worker = null;
        errors = new ArrayList<>();

        // Content panel
        GridBagLayout layout = new GridBagLayout();
        layout.columnWidths = new int[]{0};
        layout.columnWeights = new double[]{1.0};
        layout.rowHeights = new int[]{0, 0, 0, 0};
        layout.rowWeights = new double[]{0.0, 0.0, 1.0, 0.0};
        JPanel contentPanel = new JPanel(layout);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setContentPane(contentPanel);

        // Stage progress label
        progressLabel = new JLabel("Loading inventory...");
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.fill = GridBagConstraints.BOTH;
        labelConstraints.gridx = 0;
        labelConstraints.gridy = 0;
        labelConstraints.insets = new Insets(0, 0, 2, 0);
        contentPanel.add(progressLabel, labelConstraints);

        // Overall progress bar
        progressBar = new JProgressBar();
        GridBagConstraints barConstraints = new GridBagConstraints();
        barConstraints.fill = GridBagConstraints.BOTH;
        barConstraints.gridx = 0;
        barConstraints.gridy = 1;
        barConstraints.insets = new Insets(0, 0, 2, 0);
        contentPanel.add(progressBar, barConstraints);

        // History text area
        progressArea = new JTextArea();
        progressArea.setEditable(false);
        GridBagConstraints areaConstraints = new GridBagConstraints();
        areaConstraints.fill = GridBagConstraints.BOTH;
        areaConstraints.gridx = 0;
        areaConstraints.gridy = 2;
        areaConstraints.insets = new Insets(0, 0, 10, 0);
        contentPanel.add(new JScrollPane(progressArea), areaConstraints);

        // Cancel button
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener((e) -> {
            if (worker != null)
                worker.cancel(false);
        });
        GridBagConstraints cancelConstraints = new GridBagConstraints();
        cancelConstraints.gridx = 0;
        cancelConstraints.gridy = 3;
        contentPanel.add(cancelButton, cancelConstraints);

        pack();
    }

    /**
     * Make this dialog visible and then begin loading the inventory.  Block until it is
     * complete, and then return the newly-created Inventory.
     *
     * @return the #Inventory that was created.
     */
    public Inventory createInventory(File file)
    {
        worker = new InventoryLoadWorker(file);
        worker.execute();
        setVisible(true);
        progressArea.setText("");
        try
        {
            return worker.get();
        }
        catch (InterruptedException | ExecutionException e)
        {
            JOptionPane.showMessageDialog(null, "Error loading inventory: " + e.getCause().getMessage() + ".", "Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
            return new Inventory();
        }
        catch (CancellationException e)
        {
            return new Inventory();
        }
    }
}
