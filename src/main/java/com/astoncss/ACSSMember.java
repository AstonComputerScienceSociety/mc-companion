package com.astoncss;

import java.util.Calendar;
import java.util.Objects;

public class ACSSMember {
    public final int studentID;
    public final int yearVerified;

    public ACSSMember(int studentID, int yearVerified) {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        // If we're adding new members that were verified that long ago, there is a problem.
        if (yearVerified > currentYear || yearVerified < currentYear - 10) {
            throw new RuntimeException("Invalid year verified: " + yearVerified);
        }

        this.studentID = studentID;
        this.yearVerified = yearVerified;
    }

    public ACSSMember(int studentID) {
        this.studentID = studentID;
        this.yearVerified = Calendar.getInstance().get(Calendar.YEAR);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        return (this.studentID == ((ACSSMember) obj).studentID && this.yearVerified == ((ACSSMember) obj).yearVerified);
    }

    @Override
    public int hashCode() {
        return Objects.hash(studentID, yearVerified);
    }
}
