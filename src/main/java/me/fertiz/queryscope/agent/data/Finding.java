package me.fertiz.queryscope.agent.data;

public sealed interface Finding
        permits NPlusOne, RepeatedWrite, DuplicateQuery, SlowQuery, LargeResult, TooManyQueries, SlowMethod {
}
