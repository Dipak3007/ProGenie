<%@page contentType="text/html" pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html lang="en">

<head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Admin Login</title>
    <link rel="stylesheet" href="assets/css/login.css" />
    <link rel="icon" href="assets/images/logo.png" type="image/x-icon">
    <script src="https://kit.fontawesome.com/64d58efce2.js" crossorigin="anonymous"></script>
</head>

<body>
    <input type="hidden" id="status" value="<%= request.getAttribute("status") %>" />
    <div class="container">
        <div class="forms-container">
            <div class="signin-signup">
                <form action="AdminLogin" method="post" class="sign-in-form">
                    <h2 class="title">Genie Sign in</h2>
                    <div class="input-field">
                        <i class="fas fa-user"></i>
                        <input type="text" placeholder="Username" id="luname" name="luname" required />
                    </div>
                    <div class="input-field">
                        <i class="fas fa-lock"></i>
                        <input type="password" placeholder="Password" id="lpass" name="lpass" required />
                    </div>
                    <input type="submit" value="Login" class="btn solid newbtn" />
                    <p class="social-text">Or Sign in with social platforms</p>
                    <div class="social-media">
                        <a href="#" class="social-icon">
                            <i class="fab fa-facebook-f"></i>
                        </a>
                        <a href="#" class="social-icon">
                            <i class="fab fa-twitter"></i>
                        </a>
                        <a href="#" class="social-icon">
                            <i class="fab fa-google"></i>
                        </a>
                        <a href="#" class="social-icon">
                            <i class="fab fa-linkedin-in"></i>
                        </a>
                    </div>
                </form>
                <form action="AdminRegister" method="post" class="sign-up-form">
                    <h2 class="title">Genie Sign up</h2>
                    <div class="input-field">
                        <i class="fas fa-user"></i>
                        <input type="text" placeholder="Username" id="uname" name="uname" required />
                    </div>
                    <div class="input-field">
                        <i class="fas fa-envelope"></i>
                        <input type="email" placeholder="Email" id="email" name="email" required />
                    </div>
                    <div class="input-field">
                        <i class="fas fa-briefcase"></i>
                        <input type="text" placeholder="Profession" id="profession" name="profession" />
                    </div>
                    <div class="input-field">
                        <i class="fas fa-phone"></i>
                        <input type="text" placeholder="Contact Number" id="phone" name="phone" required />
                    </div>
                    <div class="input-field">
                        <i class="fas fa-lock"></i>
                        <input type="password" placeholder="Password" id="pass" name="pass" required />
                    </div>
                    <div class="input-field">
                        <i class="fas fa-lock"></i>
                        <input type="password" placeholder="Re-Type Password" id="repass" name="repass" required />
                    </div>
                    <input type="submit" class="btn newbtn" value="Sign up" />
                    <p class="social-text">Or Sign up with social platforms</p>
                    <div class="social-media">
                        <a href="#" class="social-icon">
                            <i class="fab fa-facebook-f"></i>
                        </a>
                        <a href="#" class="social-icon">
                            <i class="fab fa-twitter"></i>
                        </a>
                        <a href="#" class="social-icon">
                            <i class="fab fa-google"></i>
                        </a>
                        <a href="#" class="social-icon">
                            <i class="fab fa-linkedin-in"></i>
                        </a>
                    </div>
                </form>
            </div>
        </div>

        <div class="panels-container">
            <div class="panel left-panel">
                <div class="content">
                    <h3>New here ?</h3>
                    <p>
                        Welcome to ProGenie!<br>A reliable way to find and book home services in your area.
                    </p>
                    <button class="btn transparent" id="sign-up-btn">Sign up</button>
                </div>
                <img src="assets/images/Wishes.svg" class="image" alt="" />
            </div>
            <div class="panel right-panel">
                <div class="content">
                    <h3>Already have an account?</h3>
                    <p>&nbsp;</p>
                    <button class="btn transparent" id="sign-in-btn">Sign in</button>
                </div>
                <img src="assets/images/undraw_press_play_bx2d.svg" class="image"
                    alt="" />
            </div>
        </div>
    </div>
    <script src="assets/script/login_new.js"></script>
    <script src="https://unpkg.com/sweetalert/dist/sweetalert.min.js"></script>
    <link rel="stylesheet" href="alert/dist/sweetalert.css">
        <script type="text/javascript">
            var status=document.getElementById("status").value;
            if(status === "failed"){
                swal("Sorry","Wrong Username or Password","error");
            }
        </script>
    <script>
        const signup=document.querySelector(".sign-up-form");
        signup.addEventListener("submit",function(e){
            const pass= document.getElementById("pass");
            const repass= document.getElementById("repass");
            if (pass.value !== repass.value) {
                alert("Re-typed password didn't matched!");
                e.preventDefault();
            }
        });
        
        var status=document.getElementById("status").value;
        if(status === "success"){
            alert("Registration Successful!");
        }
    </script>
</body>

</html>
