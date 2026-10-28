package me.fertiz.queryscope.agent.findings;

public sealed interface Finding
        permits NPlusOne, RepeatedWrite, DuplicateQuery, SlowQuery, LargeResult, TooManyQueries, SlowMethod {

    FindingKind kind();
}
