# Your project, documented

This is a starter guide for **your project**. Use it to help developers understand what the project does, how to get it running, and where to find the important pieces.

## What belongs here?

Good developer documentation answers a few practical questions:

- **What is this project?** Explain its purpose and who it is for.
- **How do I run it?** Point to [Getting started](#/getting-started) for setup instructions.
- **How is it organized?** Describe the important folders in [Project structure](#/project-structure).
- **How do I configure it?** List settings and environment variables in [Configuration](#/configuration).

## A simple writing pattern

For each feature, describe the goal first, then show the steps or code someone needs. Keep examples small enough to copy and add context when a command or setting is not obvious.

> Tip: Keep documentation close to the code it describes. Update both when behavior changes.

# Diagram

``` mermaid
---
config:
  layout: elk
---
flowchart TB
 subgraph s1["Olive"]
        n5["Shop menu"]
  end
 subgraph s2["Daily Digest"]
        n7["Article list"]
        n11["Account view"]
        n12["Read article"]
  end
 subgraph s3["Better Calculator"]
        n8["Calculator Screen"]
  end
 subgraph s4["Game Disguise"]
        n9["Play game"]
  end
 subgraph s5["Olive Branch"]
        n14["Safe App Screen"]
        n15["Messages screen"]
        n17["Account History"]
        n16["Safety Plan"]
        n18["Add friend"]
        n19["Accept friend request"]
        n20["Open chat"]
        n21["Send/Recieve messages"]
  end
    n10["Check User Disguise type"] --> s2 & s3 & s1 & s4
    n6["App opens"] --> n10
    n7 --> n11 & n12
    n7 -- Trigger phrase --> n13["Login"]
    n5 -- Trigger phrase/ Discrete pattern --> n13
    n13 -- Login Success --> s5
    n13 -- Login Fail --> n10
    n14 --> n15 & n16 & n17
    n15 --> n18 & n19 & n20
    n20 --> n21
    s3 -- Pin entry --> s5
    n13 -- Failed attempt logged --> n17
    s4 -- Discrete pattern login --> s5

    n11@{ shape: subproc}
    n12@{ shape: subproc}
    n15@{ shape: subproc}
    n17@{ shape: subproc}
    n16@{ shape: subproc}
    n18@{ shape: manual-input}
    n19@{ shape: manual-input}
    n20@{ shape: subproc}
    n21@{ shape: subproc}
    n10@{ shape: decision}
    n6@{ shape: rect}
    n13@{ shape: decision}
```

