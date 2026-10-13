# CLI

`adm.py` calls the public REST API with a read-only API key (Account → API keys). Standard library
only; run `python adm.py --help`.

```text
adm login                       # saves the key, owner-only
adm ask "What did I decide yesterday?"
adm day today
adm search "Kafka"
adm decisions --days 7          # also: tasks, commitments, ideas, questions
adm --json day yesterday        # machine-readable output
```

`adm highlights` isn't offered: Highlights and Quick Notes are markers that stay on the phone.
Tests: `python -m unittest test_adm` (from this folder).
