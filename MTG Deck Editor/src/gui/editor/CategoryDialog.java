package gui.editor;

import gui.filter.FilterDialog;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.regex.Matcher;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import database.Deck;

/**
 * This class represents a dialog for creating a new category for a decklist or editing an existing
 * one.  It has a field for the category's name as well as a panel for customizing the filter for
 * the category's view of cards.  Each category must have a unique name.
 * 
 * @author Alec Roelke
 */
@SuppressWarnings("serial")
public class CategoryDialog extends FilterDialog
{
	/**
	 * Text field for the category's name.
	 */
	private JTextField nameField;
	/**
	 * Master list of cards in the deck.
	 */
	private Deck deck;
	/**
	 * CategoryPanel that will either be returned or be edited.
	 */
	private CategoryPanel category;
	
	/**
	 * Create a new CategoryDialog.
	 * 
	 * @param owner Owner Component of this CategoryDialog
	 * @param list Master list of Cards in the deck
	 */
	public CategoryDialog(EditorFrame owner)
	{
		super(SwingUtilities.windowForComponent(owner), "Advanced Filter");
		setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		
		deck = owner.deck;
		category = null;
		
		// Name editing field
		JPanel namePanel = new JPanel();
		namePanel.setBorder(new EmptyBorder(5, 5, 5, 5));
		namePanel.setLayout(new BoxLayout(namePanel, BoxLayout.X_AXIS));
		namePanel.add(new JLabel("Filter Name:"));
		nameField = new JTextField();
		namePanel.add(nameField);
		getContentPane().add(namePanel, BorderLayout.NORTH);
		
		// Panel containing buttons to close the CategoryDialog
		JPanel closePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		getContentPane().add(closePanel, BorderLayout.SOUTH);
		
		// OK button for accepting changes and creating/updating a category
		JButton okButton = new JButton("OK");
		okButton.addActionListener(new OKListener());
		closePanel.add(okButton);
		getRootPane().setDefaultButton(okButton);

		// Button for rejecting changes and not creating a category or updating an existing
		// one
		JButton cancelButton = new JButton("Cancel");
		cancelButton.addActionListener((e) -> {category = null; reset(); setVisible(false);});
		closePanel.add(cancelButton);
		
		pack();
		
		// When the window closes, reset the filters and dispose of it and make it invisible
		addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				category = null;
				reset();
				setVisible(false);
			}
		});
	}
	
	/**
	 * Reset this CategoryDialog to its initial state, with only one filter
	 * set to a name filter.
	 */
	@Override
	public void reset()
	{
		nameField.setText("");
		super.reset();
	}
	
	/**
	 * Parse a String for filters and create the appropriate filter panels and set their values.
	 * This should look similar to the parent FilterDialog's argument, except the name precedes
	 * the opening enclosure inside quotes and separated from it by a space.
	 * 
	 * @param n Name of the category
	 * @param s String to parse
	 * @see FilterDialog#setContents(String)
	 */
	public void setContents(String n, String s)
	{
		nameField.setText(n.trim());
		super.setContents(s);
	}
	
	/**
	 * Parse a String for filters and create the appropriate filter panels and set their values.
	 * This should look similar to the parent FilterDialog's argument, except the name precedes
	 * the opening enclosure inside quotes and separated from it by a space.
	 * 
	 * @param s String to parse
	 * @see FilterDialog#setContents(String)
	 */
	@Override
	public void setContents(String s)
	{
		Matcher m = Deck.CATEGORY_PATTERN.matcher(s);
		if (m.matches())
			setContents(m.group(1).trim(), m.group(4));
		else
			throw new IllegalArgumentException("Illegal content string \"" + s + "\"");
	}
	
	/**
	 * @return The name of the category to be created.
	 */
	public String name()
	{
		return nameField.getText();
	}
	
	/**
	 * @return The String representation of this CategoryDialog's filter.
	 */
	public String repr()
	{
		return super.toString();
	}
	
	/**
	 * Show the filter dialog and wait until a filter is customized, then return a new category
	 * from it.  See the OK button action for details on how the category is created.
	 * 
	 * @return The panel that should be displayed for the new category, or <code>null</code>
	 * if the window was closed or the action cancelled.
	 */
	public CategoryPanel createNewCategory()
	{
		category = null;
		setVisible(true);
		return category;
	}
	
	/**
	 * Show the dialog and wait for the category to be customized, and then edit the given category.
	 * See the OK button action for details on how the category is edited.
	 * 
	 * @param toEdit Category to edit.
	 * @return <code>true</code> if the category was edited, and <code>false</code> if the
	 * dialog was closed or the action cancelled (or if an error ocurred).
	 */
	public boolean editCategory(CategoryPanel toEdit)
	{
		if (!deck.containsCategory(toEdit.name()))
		{
			JOptionPane.showMessageDialog(null, "Decklist must contain category \"" + toEdit.name() + "\".", "Error", JOptionPane.ERROR_MESSAGE);
			reset();
			return false;
		}
		
		category = toEdit;
		setContents(toEdit.toString());
		setVisible(true);
		return category != null;
	}
	
	/**
	 * @return A String representation of this CategoryDialog.
	 */
	@Override
	public String toString()
	{
		return name() + " " + super.toString();
	}
	
	/**
	 * This class represents the action that must be taken when the OK button is pressed.  That action is
	 * to close the dialog and return the category that was created or edit the category to edit.
	 * 
	 * @author Alec Roelke
	 */
	private class OKListener implements ActionListener
	{
		@Override
		public void actionPerformed(ActionEvent e)
		{
			if (nameField.getText().isEmpty())
				JOptionPane.showMessageDialog(null, "Categories must have names.", "Error", JOptionPane.ERROR_MESSAGE);
			else if (nameField.getText().contains("<"))
				JOptionPane.showMessageDialog(null, "Category names cannot contain the character '<'.", "Error", JOptionPane.ERROR_MESSAGE);
			else
			{
				try
				{
					if (category != null)
						category.edit(nameField.getText(), CategoryDialog.super.toString(), filter());
					else
						category = new CategoryPanel(nameField.getText(), CategoryDialog.super.toString(), filter(), deck);
					setVisible(false);
					reset();
				}
				catch (Exception x)
				{
					JOptionPane.showMessageDialog(null, x.getMessage() + ".", "Error", JOptionPane.ERROR_MESSAGE);
				}
			}
		}
	}
}
