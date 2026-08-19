using System.Text;

namespace FxTools.Core.Infrastructure;

public sealed class BoundedTextBuffer
{
    private readonly object sync = new();
    private readonly Queue<string> entries = new();
    private readonly int maxEntries;
    private readonly int maxCharacters;
    private readonly int maxEntryCharacters;
    private int characters;

    public BoundedTextBuffer(int maxEntries, int maxCharacters, int maxEntryCharacters)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(maxEntries, 1);
        ArgumentOutOfRangeException.ThrowIfLessThan(maxCharacters, 1);
        ArgumentOutOfRangeException.ThrowIfLessThan(maxEntryCharacters, 1);
        this.maxEntries = maxEntries;
        this.maxCharacters = maxCharacters;
        this.maxEntryCharacters = Math.Min(maxEntryCharacters, maxCharacters);
    }

    public int Count
    {
        get { lock (sync) { return entries.Count; } }
    }

    public int CharacterCount
    {
        get { lock (sync) { return characters; } }
    }

    public void Add(string? text)
    {
        string value = text ?? string.Empty;
        if (value.Length > maxEntryCharacters)
        {
            value = string.Concat(value.AsSpan(0, maxEntryCharacters), "\n[内容已截断]");
            if (value.Length > maxCharacters)
            {
                value = value[..maxCharacters];
            }
        }

        lock (sync)
        {
            entries.Enqueue(value);
            characters += value.Length;
            while (entries.Count > maxEntries || characters > maxCharacters)
            {
                characters -= entries.Dequeue().Length;
            }
        }
    }

    public IReadOnlyList<string> Snapshot()
    {
        lock (sync)
        {
            return entries.ToArray();
        }
    }

    public string SnapshotText()
    {
        lock (sync)
        {
            if (entries.Count == 0)
            {
                return string.Empty;
            }

            StringBuilder builder = new(Math.Min(characters + entries.Count, maxCharacters));
            foreach (string entry in entries)
            {
                if (builder.Length > 0)
                {
                    builder.AppendLine();
                }
                builder.Append(entry);
            }
            return builder.ToString();
        }
    }

    public void Clear()
    {
        lock (sync)
        {
            entries.Clear();
            characters = 0;
        }
    }
}
