package editor.database.card;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import editor.collection.CardList;
import editor.collection.category.CategorySpec;
import editor.collection.deck.Deck;
import editor.database.characteristics.CardAttribute;
import editor.util.CollectionUtils;

/**
 * This class represents a method of formatting a card into a string.  It takes
 * a format string, which consists of arbitrary characters and format specifiers,
 * and replaces the format specifiers with the corresponding values of a card
 * in a deck.  A format specifier consists of the name of a {@link CardAttribute}
 * surrounded by braces ({}).
 *
 * @author Alec Roelke
 */
public class CardFormat
{
    /**
     * This class represents a "fake" card entry that reports static data about a card.
     * It always returns 1 for count, the card's release date for date, and an empty
     * set for categories.
     *
     * @author Alec Roelke
     */
    private static class FakeEntry implements CardList.Entry
    {
        /**
         * Card so the release date can be known.
         */
        private final Card card;

        /**
         * Create a new FakeEntry.
         *
         * @param c card for the new entry
         */
        public FakeEntry(Card c)
        {
            card = c;
        }

        @Override
        public Card card()
        {
            return card;
        }

        @Override
        public Set<CategorySpec> categories()
        {
            return Collections.emptySet();
        }

        @Override
        public int count()
        {
            return 1;
        }

        @Override
        public LocalDate dateAdded()
        {
            return card.expansion().releaseDate;
        }

    }

    /**
     * Format specifier for a card.
     */
    public final String format;

    /**
     * Create a new CardFormat.
     *
     * @param pattern format specifier for a card
     */
    public CardFormat(String pattern)
    {
        format = pattern;
    }

    /**
     * Format a card using this CardFormat's format specifier.  Deck-dependent
     * values will be statically assigned:
     * {@link CardAttribute#COUNT}: 1
     * {@link CardAttribute#CATEGORIES}: empty set
     * {@link CardAttribute#DATE_ADDED}: the card's release date
     *
     * @param card card to format
     * @return the formatted string
     */
    public String format(Card card)
    {
        return format(new FakeEntry(card));
    }

    /**
     * Format a card using this CardFormat's format specifier.  Deck-dependent
     * values will be taken from the entry provided.
     *
     * @param card entry for the card to format
     * @return the formatted string
     */
    public String format(CardList.Entry card)
    {
        String pattern = format;
        for (CardAttribute type : CardAttribute.values())
        {
            String replacement = '{' + type.toString().toLowerCase() + '}';
            switch (type)
            {
            case MANA_COST:
            case POWER:
            case TOUGHNESS:
            case LOYALTY:
                pattern = pattern.replace(replacement, String.join(' ' + Card.FACE_SEPARATOR + ' ',
                        ((List<?>)card.get(type)).stream().map(String::valueOf).collect(Collectors.toList())));
                break;
            case CMC:
                pattern = pattern.replace(replacement, String.join(' ' + Card.FACE_SEPARATOR + ' ',
                        CollectionUtils.convertToList(card.get(type), Double.class).stream().map((n) -> {
                            if (n == n.intValue())
                                return Integer.toString(n.intValue());
                            else
                                return n.toString();
                        }).collect(Collectors.toList())));
                break;
            case COLORS:
            case COLOR_IDENTITY:
                pattern = pattern.replace(replacement, String.join(",",
                        ((List<?>)card.get(type)).stream().map(String::valueOf).collect(Collectors.toList())));
                break;
            case CATEGORIES:
                pattern = pattern.replace(replacement, String.join(",",
                        CollectionUtils.convertToSet(card.get(type), CategorySpec.class).stream().map(CategorySpec::getName).sorted().collect(Collectors.toList())));
                break;
            case DATE_ADDED:
                pattern = pattern.replace(replacement, Deck.DATE_FORMATTER.format((LocalDate)card.get(type)));
                break;
            default:
                pattern = pattern.replace(replacement, String.valueOf(card.get(type)));
                break;
            }
        }
        return pattern;
    }
}
