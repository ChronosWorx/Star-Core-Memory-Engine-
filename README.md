# STAR-Core Memory Engine

**A Drop-in Infrastructure for Infinite Horizon Memory in Large Language Models.**

Developed by Joshua Knoechelman | Chronos Worx Research
License: CC BY-NC 4.0

## The Context Window Problem is Dead
Current retrieval-augmented inference systems (RAG) and massive-context frontier models operate under a critical fallacy: they treat the context window as a static information container. This forces models into $O(N^2)$ computational degradation, resulting in lost data, latency spikes, and massive cloud bills.

**STAR (Structured Tree with Active Retrieval)** completely abandons this approach. It treats the context window as a highly constrained **CPU Scratch Pad**. By decoupling persistent knowledge storage from active reasoning, STAR enables edge-deployed, heavily quantized models (like a 4B parameter model running natively on a smartphone) to access and synthesize knowledge stores orders of magnitude larger than their native token ceilings.

### The Mathematics of Scalability
Unlike standard sliding-window or flat-file vector approaches where capacity scales linearly ($C$), the STAR 2.0 hierarchical index scales superlinearly. 

Effective Knowledge Capacity is defined as:
$$K = \left(\frac{C \times I}{T}\right)^L \times (C \times (1-I))$$

Where:
* `C` = Native Context Window Tokens
* `I` = Index Zone Allocation (fraction of `C`)
* `T` = Token size per Semantic Tag
* `L` = Granularity Level of the Tree

A standard 128K context model utilizing STAR 2.0 achieves an effective capacity exceeding **15 billion tokens** before experiencing structural degradation. 

## Installation & Usage

STAR-Core is designed to be entirely model-agnostic. It does not require retraining, fine-tuning, or altering model weights (Capability Injection). It operates at the deterministic orchestration layer.

### 1. Initialization
Pass any local directory path into the Engine. It works on Android (`filesDir`), Windows, Linux, or macOS.

```kotlin
import com.chronosworx.starcore.StarMemoryEngine
import java.io.File

// Initialize the engine with your model's strict token ceiling
val memoryEngine = StarMemoryEngine(
    baseDirectory = File("/path/to/local/storage"),
    maxTokens = 4096 // Gemma 4B target budget
)
