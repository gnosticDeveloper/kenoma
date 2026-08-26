package common.utils;

public class StringUtils {

    private StringUtils() {}

    public static boolean isValidPassword(String password) {
        if (password == null) return false;
        if (password.length() < 12 || password.length() > 72) return false;
        boolean hasUpper = password.codePoints().anyMatch(Character::isUpperCase);
        boolean hasLower = password.codePoints().anyMatch(Character::isLowerCase);
        boolean hasDigit = password.codePoints().anyMatch(Character::isDigit);
        boolean hasSpecial = password.codePoints().anyMatch(cp -> !Character.isLetterOrDigit(cp));
        return hasUpper && hasLower && hasDigit && hasSpecial;
    }
}
