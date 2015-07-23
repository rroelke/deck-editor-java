package database.characteristics;

public class Loyalty implements Comparable<Loyalty>
{
	public final int value;
	
	public Loyalty(int l)
	{
		value = l;
	}
	
	public Loyalty(String l)
	{
		int loyal;
		try
		{
			loyal = Integer.valueOf(l);
		}
		catch (NumberFormatException e)
		{
			loyal = 0;
		}
		value = loyal;
	}
	
	@Override
	public int compareTo(Loyalty other)
	{
		if (value < 1 && other.value < 1)
			return 0;
		else if (value < 1)
			return 1;
		else if (other.value < 1)
			return -1;
		else
			return value - other.value;
	}
	
	@Override
	public String toString()
	{
		return value > 0 ? String.valueOf(value) : "";
	}
}
