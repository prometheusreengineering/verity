package ing.prometheusreengineer.verity.cli;

import ing.prometheusreengineer.verity.core.auth.AccountService;
import ing.prometheusreengineer.verity.core.auth.MicrosoftAuth;
import ing.prometheusreengineer.verity.core.auth.StoredAccount;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "account", description = "Manage Microsoft accounts (profiles use offline accounts unless account.type is msa).",
        subcommands = {AccountCommand.Login.class, AccountCommand.ListCmd.class, AccountCommand.Remove.class, AccountCommand.Token.class})
class AccountCommand implements Runnable {
    @CommandLine.ParentCommand
    VerityCli root;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    AccountService service() throws Exception {
        return new AccountService(root.context());
    }

    PrintWriter out() {
        return spec.commandLine().getOut();
    }

    @Command(name = "login", description = "Sign in with a Microsoft account using a device code.")
    static class Login implements Callable<Integer> {
        @CommandLine.ParentCommand
        AccountCommand parent;

        @Override
        public Integer call() throws Exception {
            AccountService s = parent.service();
            MicrosoftAuth auth = s.microsoft();
            MicrosoftAuth.DeviceCode code = auth.requestDeviceCode();
            parent.out().println("Open " + code.verificationUri() + " and enter the code: " + code.userCode());
            parent.out().println("Waiting for sign-in...");
            parent.out().flush();
            StoredAccount a = auth.awaitDeviceCode(code);
            s.store().put(a);
            parent.out().println("Signed in as " + a.name + " (" + a.id + ")");
            parent.out().flush();
            return 0;
        }
    }

    @Command(name = "list", description = "List stored accounts.")
    static class ListCmd implements Callable<Integer> {
        @CommandLine.ParentCommand
        AccountCommand parent;

        @Override
        public Integer call() throws Exception {
            var all = parent.service().store().list();
            if (all.isEmpty()) parent.out().println("No Microsoft accounts. Sign in with: verity account login");
            for (StoredAccount a : all) parent.out().println(a.name + "  " + a.id);
            parent.out().flush();
            return 0;
        }
    }

    @Command(name = "rm", aliases = "logout", description = "Forget a stored account.")
    static class Remove implements Callable<Integer> {
        @CommandLine.ParentCommand
        AccountCommand parent;

        @Parameters(paramLabel = "NAME_OR_ID")
        String id;

        @Override
        public Integer call() throws Exception {
            boolean removed = parent.service().store().remove(id);
            parent.out().println(removed ? "Removed " + id : "No such account " + id);
            parent.out().flush();
            return removed ? 0 : VerityCli.CONFIG_ERROR;
        }
    }

    @Command(name = "ci-token", description = "Print the stored refresh token of an account, to put into the "
            + AccountService.ENV_REFRESH_TOKEN + " CI secret. Treat it like a password.")
    static class Token implements Callable<Integer> {
        @CommandLine.ParentCommand
        AccountCommand parent;

        @Parameters(paramLabel = "NAME_OR_ID")
        String id;

        @Override
        public Integer call() throws Exception {
            var a = parent.service().store().find(id);
            if (a.isEmpty()) {
                parent.out().println("No such account " + id);
                return VerityCli.CONFIG_ERROR;
            }
            parent.out().println(a.get().msRefreshToken);
            parent.out().flush();
            return 0;
        }
    }
}
