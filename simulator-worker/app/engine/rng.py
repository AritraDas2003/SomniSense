"""
SomniSense Virtual Simulator Multi-Stream Seeded PRNG Manager (S1).
Provides independent reproducible PRNG streams via NumPy PCG64 + SeedSequence spawning.
"""

from __future__ import annotations

import secrets
from typing import NamedTuple, Optional
from numpy.random import Generator, PCG64, SeedSequence


class RNGStreams(NamedTuple):
    events: Generator
    physiology: Generator
    noise: Generator


class SeededRNGManager:
    """Manages three isolated PRNG streams: events, physiology, and noise."""

    def __init__(self, master_seed: Optional[int] = None) -> None:
        self._master_seed = master_seed
        self._resolved_seed = secrets.randbits(32) if master_seed is None else int(master_seed)
        self._seed_sequence = SeedSequence(self._resolved_seed)
        children = self._seed_sequence.spawn(3)
        self._events_gen = Generator(PCG64(children[0]))
        self._physiology_gen = Generator(PCG64(children[1]))
        self._noise_gen = Generator(PCG64(children[2]))

    @property
    def master_seed(self) -> Optional[int]:
        return self._master_seed

    @property
    def resolved_seed(self) -> int:
        return self._resolved_seed

    @property
    def events(self) -> Generator:
        return self._events_gen

    @property
    def physiology(self) -> Generator:
        return self._physiology_gen

    @property
    def noise(self) -> Generator:
        return self._noise_gen

    @property
    def streams(self) -> RNGStreams:
        return RNGStreams(
            events=self._events_gen,
            physiology=self._physiology_gen,
            noise=self._noise_gen,
        )
