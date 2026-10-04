package fixture;
public final class DebugSubject {
    public static int result;
    public static int calculate(int input) {
        int doubled=input*2;
        int answer=doubled+7;
        return answer;
    }
    public static void main(String[] args) throws Exception {
        System.out.println("READY");
        for(int i=0;;i++){result=calculate(i);Thread.sleep(20);}
    }
}
